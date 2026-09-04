package com.hmdp.voucher.listener;

import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.common.constants.CacheConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.outbox.OutboxConsumeService;

import com.hmdp.voucher.domain.Voucher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;


import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.common.constants.RedisConstants.CACHE_VOUCHER_LIST_KEY;


@Slf4j
@Component
public class VoucherCacheListener {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OutboxConsumeService outboxConsumeService;

    @Resource
    private Cache<Long, List<Voucher>> voucherListCache;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = CacheConstants.QUEUE_VOUCHER_CACHE,
                    durable = "true",
                    arguments = @Argument(name = "x-queue-mode", value = "lazy")
            ),
            exchange = @Exchange(
                    name = CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE,
                    type = "fanout"
            )
    ))
    public void handleCacheSync(CacheSyncMessage message, Message amqp) {
        log.debug("VoucherCacheListener 收到缓存同步消息，type={}", message.getType());
        if (!outboxConsumeService.tryMarkConsumed(amqp.getMessageProperties().getMessageId())) {
            return;
        }
        if (!"VOUCHER".equals(message.getType())) {
            log.debug("VoucherCacheListener 忽略消息类型：{}", message.getType());
            return;
        }
        Long shopId = message.getShopId();
        if (shopId != null) {
            voucherListCache.invalidate(shopId);
            stringRedisTemplate.delete(CACHE_VOUCHER_LIST_KEY + shopId);
            log.debug("已清除Voucher列表缓存，shopId={}", shopId);
        }
    }
}