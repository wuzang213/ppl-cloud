package com.hmdp.search.service.impl;

import com.hmdp.search.service.ISearchIndexRebuildService;
import com.hmdp.search.service.ISearchIndexService;
import com.hmdp.search.service.ISearchSourceReader;
import com.hmdp.search.service.RebuildRegistry;
import com.hmdp.search.config.SearchProperties;
import com.hmdp.search.dao.SearchStateDao;
import com.hmdp.search.domain.RebuildOutcome;
import com.hmdp.search.domain.SearchRebuildTask;
import com.hmdp.search.es.EsClient;
import com.hmdp.search.es.EsIndexNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 全量初始化与重建：影子索引 + 别名原子切换 + 重建期间双写追平。用 Redis 租约锁保证互斥；重建期间变更靠事件重放追平（不依赖 update_time 区间扫描）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchIndexRebuildServiceImpl implements ISearchIndexRebuildService {

    /** 重建期间关刷新，吞吐显著提升 */
    private static final String REFRESH_OFF = "-1";

    private static final String REFRESH_ON = "1s";

    /** 释放租约锁：仅当 value 还是自己写入的那把才删。用 Lua 而非 GET 之后再 DEL，
     * 因为"比较—删除"必须原子 —— 否则比较通过之后、删除之前锁刚好过期并被别人重占，就会误删别人的锁。
     * 脚本内容见 {@code resources/lua/release_lock.lua}。 */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT;

    static {
        RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>();
        RELEASE_LOCK_SCRIPT.setLocation(new ClassPathResource("lua/release_lock.lua"));
        RELEASE_LOCK_SCRIPT.setResultType(Long.class);
    }

    private final EsClient esClient;

    private final RebuildRegistry rebuildRegistry;

    private final ISearchIndexService indexService;

    private final ISearchSourceReader reader;

    private final SearchStateDao stateDao;

    private final SearchProperties properties;

    private final StringRedisTemplate stringRedisTemplate;

    /** 启动时自愈：别名缺失或索引为空则自动全量重建。
     * ⚠️ 刻意不 fail fast：ES 没起来服务仍应能启动（搜索接口返回空/报错，但索引同步消息会进死信表等重试），否则 ES 成为整个服务的启动依赖。 */
    public void rebuildIfNeeded(String type) {
        try {
            String alias = EsIndexNames.aliasOf(type);
            if (!esClient.aliasExists(alias)) {
                log.warn("未发现别名 {}，触发全量初始化", alias);
                rebuild(type);
                return;
            }
            long count = esClient.count(alias);
            if (count == 0) {
                log.warn("别名 {} 指向的索引文档数为 0，触发全量初始化", alias);
                rebuild(type);
            } else {
                log.info("别名 {} 已存在且有 {} 条文档，跳过全量初始化", alias, count);
            }
        } catch (Exception e) {
            log.error("启动自检失败（ES 不可用？），跳过多量初始化，可稍后手动调 /search/admin/rebuild。type={}", type, e);
        }
    }

    /** 全量重建（幂等入口）。⚠️ 必须互斥：并发重建会互相覆盖别名、最终留下谁也说不清指向哪的索引；用 Redis {@code SET NX EX} 做租约锁（默认 {@code rebuild-lock-minutes}=30）到期自动释放，避免进程被 kill 后永久死锁。
     * ⚠️ 释放锁必须校验持有者（Lua 比较 value 再删）：若重建耗时超租约、锁已被另一个重建拿走，盲目 {@code DEL} 会删掉后者的锁、放第三个重建并发进来；@return {@link RebuildOutcome#SKIPPED} = 已有重建在跑、本次没执行，SUCCESS/FAILED = 真的跑了。 */
    public RebuildOutcome rebuild(String type) {
        if (!EsIndexNames.TYPE_BLOG.equals(type) && !EsIndexNames.TYPE_SHOP.equals(type)) {
            throw new IllegalArgumentException("不支持的索引类型: " + type);
        }
        String lockKey = "lock:search:rebuild:" + type;
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, Duration.ofMinutes(properties.getRebuildLockMinutes()));
        if (!Boolean.TRUE.equals(locked)) {
            log.warn("已有 {} 索引重建任务在执行，本次跳过", type);
            return RebuildOutcome.SKIPPED;
        }
        try {
            return doRebuild(type);
        } finally {
            // 只有锁还是自己那把时才删（GET+DEL 用 Lua 保证原子）
            stringRedisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), lockValue);
        }
    }

    private RebuildOutcome doRebuild(String type) {
        String alias = EsIndexNames.aliasOf(type);
        String prefix = EsIndexNames.indexPrefixOf(type);
        long processed = 0L;
        long taskId = -1L;
        // 提到方法作用域：失败路径要靠它清理本次自己建的半成品索引（P3-19）
        String target = null;
        try {
            int version = nextVersion(prefix);
            target = prefix + version;
            // snapshot_start 由 DB 的 NOW() 直接写入（不再从 Java 传值，见 SearchStateDao.insertRebuildTask）
            taskId = stateDao.insertRebuildTask(type, target, alias);
            log.info("开始重建索引 type={}, target={}", type, target);

            // 1) 建影子索引（关刷新）。必须"先建索引、再登记重建上下文"——
            //    否则登记之后、索引创建之前到达的变更会尝试写一个不存在的索引而报错。
            esClient.createIndex(target, type, REFRESH_OFF);
            rebuildRegistry.start(type, target);

            // 2) 在线分页扫描，边扫边 bulk 灌入影子索引（此时线上仍由旧索引提供服务）
            processed = scanAll(type, target, taskId);

            // 3) 重放重建期间发生过的变更，纠正被扫描用旧值覆盖的文档
            Set<Long> mutated = rebuildRegistry.drainBuffer(type);
            for (Long id : mutated) {
                indexService.applyToIndex(type, id, target);
            }
            processed += mutated.size();
            log.info("重建双写追平完成 type={}, 重放变更数={}", type, mutated.size());

            // 4) 打开刷新并强制 refresh，此时 count 才是真实文档数
            esClient.updateRefreshInterval(target, REFRESH_ON);
            esClient.refresh(target);
            long esCount = esClient.count(target);
            long bizCount = countBusiness(type);
            if (bizCount > 0 && esCount < bizCount) {
                throw new IllegalStateException(
                        "一致性校验未通过，中止切换别名：es=" + esCount + ", business=" + bizCount);
            }
            stateDao.updateRebuildProgress(taskId, processed, bizCount);

            // 5) 原子切换别名（一次 _aliases 请求内完成摘旧挂新，无空窗期）
            Set<String> olds = esClient.switchAlias(alias, target);

            stateDao.finishRebuildTask(taskId, "SUCCESS", processed, null);
            log.info("索引重建成功 type={}, target={}, processed={}, esCount={}, bizCount={}",
                    type, target, processed, esCount, bizCount);

            // 6) 清理孤儿影子索引：只留「当前」+「刚从别名摘下来的」回滚候选，其余全删（P3-19）
            cleanupOrphans(type, target, olds);
            return RebuildOutcome.SUCCESS;
        } catch (Exception e) {
            log.error("索引重建失败 type={}", type, e);
            if (taskId > 0) {
                stateDao.finishRebuildTask(taskId, "FAILED", processed, e.getMessage());
            }
            // 本次自己建的影子索引没被切上别名，留着只会变成孤儿 → 顺手清掉
            deleteHalfBuiltIndex(type, target);
            return RebuildOutcome.FAILED;
        } finally {
            // 无论成败都要解除"重建中"标记，否则后续变更会一直双写一个已经废弃的影子索引
            rebuildRegistry.finish(type);
        }
    }

    /** 分页扫描业务数据并 bulk 写入目标索引 */
    private long scanAll(String type, String target, long taskId) throws IOException {
        int pageSize = properties.getRebuildPageSize();
        int bulkSize = properties.getBulkSize();
        int current = 1;
        long processed = 0L;
        Map<String, Object> batch = new LinkedHashMap<>();

        while (true) {
            List<Object> docs = reader.fetchPage(type, current, pageSize);
            if (docs.isEmpty()) {
                break;
            }
            for (Object raw : docs) {
                Long id = reader.idOf(raw);
                Object doc = reader.convert(type, raw);
                if (id != null && doc != null) {
                    batch.put(String.valueOf(id), doc);
                }
            }
            if (batch.size() >= bulkSize) {
                processed += flush(target, batch);
                stateDao.updateRebuildProgress(taskId, processed, processed);
            }
            if (docs.size() < pageSize) {
                // 最后一页；不足整页说明已经没有更多数据了
                break;
            }
            current++;
        }
        processed += flush(target, batch);
        stateDao.updateRebuildProgress(taskId, processed, processed);
        return processed;
    }

    private long flush(String target, Map<String, Object> batch) throws IOException {
        if (batch.isEmpty()) {
            return 0;
        }
        int failed = esClient.bulkIndex(target, batch);
        long size = batch.size();
        batch.clear();
        if (failed > 0) {
            throw new IllegalStateException("bulk 写入影子索引存在失败项，失败数=" + failed);
        }
        return size;
    }

    /** 统计业务侧文档总数（用于切换别名前的一致性校验） */
    private long countBusiness(String type) {
        return reader.collectAllIds(type, null).size();
    }

    /** 现有物理索引的最大版本号 + 1；一个都没有则从 v1 开始 */
    private int nextVersion(String prefix) throws IOException {
        int max = 0;
        for (String name : esClient.listPhysicalIndices(prefix)) {
            max = Math.max(max, versionOf(prefix, name));
        }
        return max + 1;
    }

    /** 从 {@code hmdp_blog_v2} 里取出 2；解析不出来按 -1（当作最旧）处理 */
    private int versionOf(String prefix, String index) {
        String suffix = index.startsWith(prefix) ? index.substring(prefix.length()) : index;
        try {
            return Integer.parseInt(suffix);
        } catch (NumberFormatException ignore) {
            log.debug("无法从索引名解析版本号，按最旧处理: {}", index);
            return -1;
        }
    }

    /** 清理孤儿影子索引：只留「别名当前指向的」+「刚从别名上摘下来的回滚候选」，其余 {@code <prefix>*} 物理索引一律删除。
     * ⚠️ 保留 {@code olds}（刚被摘下的）而非"版本号最大的孤儿"：进程被 kill 会留下没建完的高版本影子（如 v3 而别名还在 v2），按最大版本保留会留着半成品、反而删掉真正能回滚的 v2；且用重新查询到的别名集合做二次校验、绝不删别名指向的索引。清理失败不影响重建结论，整体吞异常只告警。 */
    private void cleanupOrphans(String type, String target, Set<String> olds) {
        try {
            String alias = EsIndexNames.aliasOf(type);
            String prefix = EsIndexNames.indexPrefixOf(type);

            Set<String> keep = new HashSet<>(esClient.indicesOfAlias(alias));
            keep.add(target);
            List<String> rollback = new ArrayList<>(olds);
            rollback.sort(Comparator.comparingInt(name -> versionOf(prefix, name)));
            int keepCount = Math.min(properties.getKeepOldVersions(), rollback.size());
            keep.addAll(rollback.subList(rollback.size() - keepCount, rollback.size()));

            List<String> deleted = new ArrayList<>();
            for (String index : esClient.listPhysicalIndices(prefix)) {
                if (keep.contains(index)) {
                    continue;
                }
                esClient.deleteIndex(index);
                deleted.add(index);
            }
            if (deleted.isEmpty()) {
                log.info("无需清理孤儿索引 type={}, 保留={}", type, keep);
            } else {
                log.warn("已清理孤儿影子索引 type={}, 删除={}, 保留={}（keepOldVersions={}）",
                        type, deleted, keep, properties.getKeepOldVersions());
            }
        } catch (Exception e) {
            log.warn("清理孤儿影子索引失败，不影响本次重建结果，type={}", type, e);
        }
    }

    /** 重建中断时删掉本次自己建的半成品索引（它没被切上别名，留着只会变成孤儿）。
     * ⚠️ 必须再查一次别名：若异常发生在 {@code switchAlias} 之后（如写重建任务收尾状态时抛错），{@code target} 已是线上索引，绝不能删。 */
    private void deleteHalfBuiltIndex(String type, String target) {
        if (target == null) {
            return;
        }
        try {
            if (esClient.indicesOfAlias(EsIndexNames.aliasOf(type)).contains(target)) {
                log.warn("重建虽失败但别名已指向 {}，保留该索引（失败发生在切换别名之后）", target);
                return;
            }
            if (esClient.indexExists(target)) {
                esClient.deleteIndex(target);
                log.info("已清理本次中断重建留下的半成品索引 {}", target);
            }
        } catch (Exception e) {
            log.warn("清理半成品索引 {} 失败（可手工 DELETE /{}），不影响重建结论", target, target, e);
        }
    }

    /** 集群里所有与搜索相关的物理索引（供运维确认/清理） */
    public Map<String, List<String>> listIndices() {
        Map<String, List<String>> result = new HashMap<>(2);
        try {
            result.put(EsIndexNames.TYPE_BLOG, esClient.listPhysicalIndices(EsIndexNames.BLOG_INDEX_PREFIX));
            result.put(EsIndexNames.TYPE_SHOP, esClient.listPhysicalIndices(EsIndexNames.SHOP_INDEX_PREFIX));
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("查询物理索引列表失败", e);
        }
    }

    @Override
    public SearchRebuildTask getLatestRebuildTask(String type) {
        return stateDao.findLatestRebuildTask(type);
    }
}
