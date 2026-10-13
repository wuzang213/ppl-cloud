package com.hmdp.shop.task;

import com.hmdp.shop.domain.Shop;
import com.hmdp.shop.mapper.ShopMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.common.constants.RedisConstants.SHOP_GEO_KEY;

/**
 * 店铺坐标的**全量重建**（{@code shop:geo:{typeId}}）。
 *
 * <p>🔴 **为什么必须有这个任务**：{@code ShopServiceImpl.queryShopByType} 带坐标时查的是
 * **Redis GEO**，而 GEO 索引<b>只能</b>由 MQ 事件（{@code SHOP_CREATED} / {@code SHOP_UPDATED}）
 * 经 {@code ShopCacheListener} 写入。于是**初始化脚本导入的历史店铺永远进不了索引** ——
 * 表现是「数据明明在库里，但带坐标的附近查询恒返 0 条」，而去掉坐标就正常（走 DB 分页）。
 * 实测新环境 {@code shop:geo*} 一个键都没有。这是**初始化不幂等**的设计缺口，不是查询 bug。
 *
 * <p>✅ 本任务是**幂等**的：{@code ZADD} 同 member 只更新 score，重复执行结果一致，
 * 因此既能在启动后跑一次，也能定时兜底（人工改了坐标、重建了库都能自愈）。
 *
 * <p>⚠️ 刻意**不**放 {@code @PostConstruct} 同步跑：Redis 不可用时会把**应用启动**拖成失败
 * （而启动失败会连带拖垮整个服务）。改为「延迟 + 定时」，Redis 抖动只表现为日志告警。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopGeoRebuildTask {

    private final StringRedisTemplate stringRedisTemplate;
    private final ShopMapper shopMapper;

    /** 开关：关掉即可完全停用本任务（默认开；这是修 bug 的兜底，不该默认关）。 */
    @Value("${hmdp.shop.geo-rebuild.enabled:true}")
    private boolean enabled;

    /**
     * 重建全部类型的 GEO 索引。
     *
     * <p>按 {@code typeId} 分组批量写入；**只处理有坐标的店铺**（没坐标的写进去也没用，
     * 且会让 Redis 里出现 score 为 NaN 的脏点）。
     *
     * <p>📌 延迟写在注解上（{@code initialDelay}）而**不是** {@code @Value}：
     * 注解参数必须是编译期常量，读不了配置。要改启动节奏就改这两个字面量。
     */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 15000)
    public void rebuildAll() {
        if (!enabled) {
            return;
        }
        try {
            List<Shop> shops = shopMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Shop>()
                            .isNotNull(Shop::getX).isNotNull(Shop::getY));
            if (shops == null || shops.isEmpty()) {
                log.info("GEO 重建跳过：库里没有带坐标的店铺");
                return;
            }
            Map<Long, List<Shop>> byType = shops.stream()
                    .filter(s -> s.getTypeId() != null)
                    .collect(Collectors.groupingBy(Shop::getTypeId));

            int written = 0;
            for (Map.Entry<Long, List<Shop>> entry : byType.entrySet()) {
                String key = SHOP_GEO_KEY + entry.getKey();
                for (Shop shop : entry.getValue()) {
                    // member=shopId，score 用经度；value 用 "经度,纬度"（与写入方 ShopCacheListener 一致）
                    stringRedisTemplate.opsForGeo().add(
                            key,
                            new org.springframework.data.geo.Point(shop.getX(), shop.getY()),
                            shop.getId().toString());
                    written++;
                }
                log.info("GEO 重建 {}：{} 家", key, entry.getValue().size());
            }
            log.info("GEO 重建完成：共 {} 家店铺，覆盖 {} 个类型", written, byType.size());
        } catch (Exception e) {
            // ⚠️ 失败只告警不抛出：这是"锦上添花"的兜底任务，不该让服务起不来。
            //    且不打 stack 到 ERROR 噪声之外——排查时看 warn 即可。
            log.warn("GEO 重建失败（不影响主流程，附近查询可能仍为空）：{}", e.getMessage());
        }
    }
}
