package com.hmdp.search.service;

import com.hmdp.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 重建上下文登记处（Redis 共享态）。在线重建时扫描会用快照旧值覆盖重建期间已发生的新变更（该变更消息已消费完、无第二次机会 → 重建完成即落后），
 * 故把变更 id 记进 buffer、扫描结束后重放纠正。上下文必须放全实例可见的 Redis：{@code search:rebuild:ctx:{type}} = 影子索引名、{@code search:rebuild:mut:{type}} = 变更 id 集合，TTL = {@code rebuild-lock-minutes + 10} 分钟（必须比租约锁更宽，否则锁还在、双写却停了）；单实例进程内 Map 在多实例下会静默丢变更。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RebuildRegistry {

    private static final String CTX_KEY_PREFIX = "search:rebuild:ctx:";

    private static final String MUT_KEY_PREFIX = "search:rebuild:mut:";

    /** 上下文/缓冲的 TTL 比租约锁多留这么多分钟，避免"锁还在、上下文先过期" */
    private static final int TTL_MARGIN_MINUTES = 10;

    /** 记录一次变更 + 顺手给两个键续期，只在"确实在重建中"时写入。用 Lua 一次往返完成，而非 {@code SADD} 之后再 {@code EXPIRE}：
     * 少一次 RTT、避免"集合已写、过期时间未设"的中间态，且 {@code exists} 判断把"重建刚好结束"的竞态一并收口，不留没人清理的野集合。 */
    private static final RedisScript<Long> RECORD_MUTATION_SCRIPT = RedisScript.of(
            "if redis.call('exists', KEYS[2]) == 1 then "
                    + "redis.call('sadd', KEYS[1], ARGV[1]) "
                    + "redis.call('expire', KEYS[1], ARGV[2]) "
                    + "redis.call('expire', KEYS[2], ARGV[2]) "
                    + "return 1 "
                    + "end "
                    + "return 0",
            Long.class);

    /** 取出并清空待重放的 id 集合。{@code SMEMBERS} + {@code DEL} 必须原子：分两次发的话，两次之间新记进来的 id 会被 {@code DEL} 连带丢掉。 */
    private static final RedisScript<List> DRAIN_SCRIPT = RedisScript.of(
            "local v = redis.call('smembers', KEYS[1]) "
                    + "redis.call('del', KEYS[1]) "
                    + "return v",
            List.class);

    private final StringRedisTemplate stringRedisTemplate;

    private final SearchProperties properties;

    public void start(String type, String shadowIndex) {
        Duration ttl = ttl();
        stringRedisTemplate.opsForValue().set(ctxKey(type), shadowIndex, ttl);
        // 上一轮（可能被 kill）残留的变更集合不能带进来，否则会把无关 id 重放一遍
        stringRedisTemplate.delete(mutKey(type));
        log.info("索引重建已开始，type={}, shadowIndex={}，上下文已写入 Redis（全实例可见），ttl={}min",
                type, shadowIndex, ttl.toMinutes());
    }

    public boolean isRebuilding(String type) {
        return shadowIndex(type) != null;
    }

    /** 当前重建目标（影子）索引；返回 {@code null} 即表示该类型不在重建中。调用方只用这一个方法判断，不要写成 {@code if (isRebuilding(t)) shadowIndex(t)} —— 两次查找之间有窗口，重建恰在中间结束时第二次会拿到 null。
     * ⚠️ Redis 读失败时按"不在重建中"处理并告警而非上抛：此路径在消息消费里，抛出去会让变更落失败表、进死信；Redis 真挂了重建本身也开不起来（锁在同一 Redis 上）。 */
    public String shadowIndex(String type) {
        try {
            return stringRedisTemplate.opsForValue().get(ctxKey(type));
        } catch (Exception e) {
            log.warn("读取重建上下文失败，按「不在重建中」处理（本次不双写影子索引）type={}", type, e);
            return null;
        }
    }

    /** 记录一次变更，扫描结束后重放（纠正可能被扫描覆盖的旧值） */
    public void recordMutation(String type, Long id) {
        if (id == null) {
            return;
        }
        try {
            stringRedisTemplate.execute(RECORD_MUTATION_SCRIPT, List.of(mutKey(type), ctxKey(type)),
                    String.valueOf(id), String.valueOf(ttl().getSeconds()));
        } catch (Exception e) {
            // 记不上就等于"这次变更少了重放机会"，最坏情况是文档停在扫描快照的旧值上，
            // 会由对账任务兜底，所以只告警，不能影响本次消息处理。
            log.warn("登记重建期变更失败（该 id 将失去重放机会）type={}, id={}", type, id, e);
        }
    }

    /** 取出并清空待重放的 id 集合（跨实例共享，任何实例记的都会在这里被取走） */
    public Set<Long> drainBuffer(String type) {
        Set<Long> drained = new LinkedHashSet<>();
        try {
            List<?> raw = stringRedisTemplate.execute(DRAIN_SCRIPT, List.of(mutKey(type)));
            if (raw == null) {
                return drained;
            }
            for (Object item : raw) {
                try {
                    drained.add(Long.valueOf(String.valueOf(item)));
                } catch (NumberFormatException ignore) {
                    log.warn("重放缓冲里出现非数字成员，已跳过：{}", item);
                }
            }
        } catch (Exception e) {
            log.warn("取出重建期变更集合失败（本次将没有可重放的 id）type={}", type, e);
        }
        return drained;
    }

    public void finish(String type) {
        try {
            stringRedisTemplate.delete(List.of(ctxKey(type), mutKey(type)));
            log.info("索引重建已结束，type={}，已停止双写影子索引（上下文已从 Redis 清除）", type);
        } catch (Exception e) {
            // 清不掉也不能让重建结论变失败；TTL 到期会自己消失
            log.warn("清除重建上下文失败（将等 TTL 自然过期）type={}", type, e);
        }
    }

    private String ctxKey(String type) {
        return CTX_KEY_PREFIX + type;
    }

    private String mutKey(String type) {
        return MUT_KEY_PREFIX + type;
    }

    private Duration ttl() {
        return Duration.ofMinutes(properties.getRebuildLockMinutes() + TTL_MARGIN_MINUTES);
    }
}
