package com.hmdp.shop.listener;

import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.common.constants.CacheConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.outbox.OutboxConsumeService;

import com.hmdp.shop.domain.Shop;
import com.hmdp.shop.domain.ShopType;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

import static com.hmdp.common.constants.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.common.constants.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.common.constants.RedisConstants.FAVORITE_SHOP_KEY;
import static com.hmdp.common.constants.RedisConstants.SHOP_GEO_KEY;


@Slf4j
@Component
public class ShopCacheListener {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OutboxConsumeService outboxConsumeService;

    @Resource
    private Cache<Long, Shop> shopCache;

    @Resource
    private Cache<String, List<ShopType>> shopTypeListCache;

    @Resource
    private RBloomFilter<String> shopBloomFilter;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = CacheConstants.QUEUE_SHOP_CACHE,
                    durable = "true",
                    arguments = @Argument(name = "x-queue-mode", value = "lazy")
            ),
            exchange = @Exchange(
                    name = CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE,
                    type = "fanout"
            )
    ))
    public void handleCacheSync(CacheSyncMessage message, Message amqp) {
        log.debug("ShopCacheListener 收到缓存同步消息，type={}, eventType={}",
                message.getType(), message.getEventType());
        if (!outboxConsumeService.tryMarkConsumed(amqp.getMessageProperties().getMessageId())) {
            return;
        }
        switch (message.getType()) {
            case "SHOP":
                Long shopId = message.getId();
                shopCache.invalidate(shopId);
                stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
                applyShopSideEffect(message);
                log.debug("清除Shop缓存，id={}", shopId);
                break;
            case "SHOP_TYPE":
                shopTypeListCache.invalidateAll();
                stringRedisTemplate.delete(CACHE_SHOP_TYPE_KEY);
                log.debug("清除ShopType列表缓存");
                break;
            case "SHOP_FAVORITE":
                applyFavoriteChange(message);
                break;
            default:
                // 当前队列只会收到广播消息，不属于本服务类型直接忽略
                log.debug("ShopCacheListener 忽略消息类型：{}", message.getType());
        }
    }

    /**
     * 执行原本放在 afterCommit 中的 Redis 副作用：GEO 坐标更新与布隆写入。
     * 迁移到 MQ 消费端后，由 outbox 保证投递可对账重试，不再依赖请求线程。
     */
    private void applyShopSideEffect(CacheSyncMessage message) {
        String eventType = message.getEventType();
        if (eventType == null) {
            return;
        }
        Long shopId = message.getId();
        switch (eventType) {
            case "SHOP_CREATED":
            case "SHOP_UPDATED":
                Map<String, Object> data = message.getData();
                if (data != null && data.get("x") != null && data.get("y") != null && data.get("typeId") != null) {
                    stringRedisTemplate.opsForGeo().add(
                            SHOP_GEO_KEY + data.get("typeId"),
                            new Point(((Number) data.get("x")).doubleValue(),
                                    ((Number) data.get("y")).doubleValue()),
                            shopId.toString());
                }
                shopBloomFilter.add(shopId.toString());
                break;
            case "SHOP_DELETED":
                // 布隆过滤器不支持删除元素，保持原有 add 语义，已删店铺查询由空值缓存兜底
                shopBloomFilter.add(shopId.toString());
                break;
            default:
                break;
        }
    }

    /**
     * 维护用户收藏店铺的 Redis Set（原 afterCommit 逻辑迁移至此，可对账重试）。
     */
    private void applyFavoriteChange(CacheSyncMessage message) {
        Map<String, Object> data = message.getData();
        if (data == null || data.get("userId") == null || data.get("shopId") == null) {
            return;
        }
        long userId = ((Number) data.get("userId")).longValue();
        String shopId = String.valueOf(data.get("shopId"));
        String key = FAVORITE_SHOP_KEY + userId;
        if (Boolean.TRUE.equals(data.get("favorite"))) {
            stringRedisTemplate.opsForSet().add(key, shopId);
        } else {
            stringRedisTemplate.opsForSet().remove(key, shopId);
        }
        log.debug("同步店铺收藏关系，userId={}, shopId={}, favorite={}", userId, shopId, data.get("favorite"));
    }
}
