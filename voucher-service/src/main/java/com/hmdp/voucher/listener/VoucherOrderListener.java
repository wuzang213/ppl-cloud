package com.hmdp.voucher.listener;

import com.hmdp.common.constants.MqConstants;
import com.hmdp.voucher.constants.VoucherConstants;
import com.hmdp.voucher.domain.VoucherOrder;
import com.hmdp.voucher.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀订单消费者
 */
@Component
@Slf4j
public class VoucherOrderListener {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private RedissonClient redissonClient;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = MqConstants.SECKILL_ORDER_QUEUE,
                    durable = "true",
                    arguments = {
                            @Argument(name = "x-queue-mode", value = "lazy"),
                            // 配置死信交换机，重试耗尽后消息自动进入死信队列
                            @Argument(name = "x-dead-letter-exchange", value = MqConstants.SECKILL_DLX_EXCHANGE),
                            @Argument(name = "x-dead-letter-routing-key", value = MqConstants.SECKILL_ORDER_DEAD_ROUTING_KEY)
                    }
            ),
            exchange = @Exchange(name = MqConstants.SECKILL_DIRECT_EXCHANGE, type = "direct"),
            key = MqConstants.SECKILL_ORDER_ROUTING_KEY
    ))
    public void handleVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        log.info("开始异步处理秒杀订单 userId={}, voucherId={}", userId, voucherId);

        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = redisLock.tryLock(10, 30, TimeUnit.SECONDS);
        if (!isLock) {
            log.error("用户{}请勿重复下单，抢锁失败", userId);
            // 业务正常失败，直接结束，ACK删除消息，不重试
            return;
        }
        try {
            boolean created = voucherOrderService.createVoucherOrder(voucherOrder);
            if (!created) {
                throw new RuntimeException(VoucherConstants.ORDER_CREATE_FAILED + ", userId=" + userId + ", voucherId=" + voucherId);
            }
            log.info("秒杀订单创建成功 userId={}, voucherId={}", userId, voucherId);
        } catch (Exception e) {
            log.error("处理秒杀订单发生系统异常 userId={}", userId, e);
            // 抛出异常，消息重回队列尝试重试
            throw new RuntimeException("订单处理异常", e);
        } finally {
            if (redisLock.isHeldByCurrentThread()) {
                redisLock.unlock();
            }
        }
    }
}