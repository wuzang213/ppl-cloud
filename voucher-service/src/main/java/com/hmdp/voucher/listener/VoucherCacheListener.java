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
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
        log.debug("VoucherCacheListener 收到缓存同步消息，type={}, eventType={}",
                message.getType(), message.getEventType());
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
        applySeckillStockSideEffect(message);
    }

    /**
     * 秒杀库存 key 的维护（原 afterCommit 逻辑迁移至此，可对账重试）：
     * 预热 / 删除 / 回补。
     */
    private void applySeckillStockSideEffect(CacheSyncMessage message) {
        String eventType = message.getEventType();
        if (eventType == null) {
            return;
        }
        Long voucherId = message.getId();
        String stockKey = "seckill:{" + voucherId + "}:stock";
        switch (eventType) {
            case "SECKILL_VOUCHER_CREATED": {
                Map<String, Object> data = message.getData();
                if (data != null && data.get("stock") != null) {
                    stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(data.get("stock")));
                    log.debug("预热秒杀库存，voucherId={}, stock={}", voucherId, data.get("stock"));
                }
                break;
            }
            case "VOUCHER_DELETED":
                stringRedisTemplate.delete(stockKey);
                log.debug("删除秒杀库存 key，voucherId={}", voucherId);
                break;
            case "SECKILL_STOCK_RESTORE": {
                // 回补库存是累加操作，必须按订单维度去重，避免 outbox 重投导致库存虚高
                Map<String, Object> data = message.getData();
                Object orderId = data == null ? null : data.get("orderId");
                if (orderId == null) {
                    log.warn("秒杀库存回补缺少 orderId，跳过。voucherId={}", voucherId);
                    break;
                }
                String dedupKey = "seckill:stock:restore:" + orderId;
                Boolean first = stringRedisTemplate.opsForValue()
                        .setIfAbsent(dedupKey, "1", 7, TimeUnit.DAYS);
                if (Boolean.TRUE.equals(first)) {
                    stringRedisTemplate.opsForValue().increment(stockKey);
                    log.debug("回补秒杀库存，voucherId={}, orderId={}", voucherId, orderId);
                } else {
                    log.debug("秒杀库存已回补过，跳过。voucherId={}, orderId={}", voucherId, orderId);
                }
                break;
            }
            default:
                break;
        }
    }
}
