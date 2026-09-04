package com.hmdp.shop.listener;

import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.common.constants.CacheConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.outbox.OutboxConsumeService;

import com.hmdp.shop.domain.Shop;
import com.hmdp.shop.domain.ShopType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.common.constants.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.common.constants.RedisConstants.CACHE_SHOP_TYPE_KEY;


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
        log.debug("ShopCacheListener 收到缓存同步消息，type={}", message.getType());
        if (!outboxConsumeService.tryMarkConsumed(amqp.getMessageProperties().getMessageId())) {
            return;
        }
        switch (message.getType()) {
            case "SHOP":
                Long shopId = message.getId();
                shopCache.invalidate(shopId);
                stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
                log.debug("清除Shop缓存，id={}", shopId);
                break;
            case "SHOP_TYPE":
                shopTypeListCache.invalidateAll();
                stringRedisTemplate.delete(CACHE_SHOP_TYPE_KEY);
                log.debug("清除ShopType列表缓存");
                break;
            default:
                // 当前队列只会收到广播消息，不属于本服务类型直接忽略
                log.debug("ShopCacheListener 忽略消息类型：{}", message.getType());
        }
    }
}