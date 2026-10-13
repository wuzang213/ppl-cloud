package com.hmdp.voucher.listener;

import com.hmdp.common.constants.MqConstants;
import com.hmdp.voucher.domain.VoucherOrder;
import com.hmdp.voucher.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/** 秒杀订单死信队列消费者：{@link VoucherOrderListener} 重试耗尽后消息进死信，此处补偿 —— 先查库确认订单确实未
 * 落库，再释放 Redis 预扣库存（INCR seckill:{voucherId}:stock）+ 删去重标记（SREM seckill:{voucherId}:order
 * {userId}）。必须先校验：{@code createVoucherOrder()} 返回 false 有两义（①DB 已有该订单 ②DB 扣库存失败），情形①回补会让库存虚高且该用户可再下单（超卖）；校验失败时不回补，认「少卖」不认「超卖」。 */
@Slf4j
@Component
public class SeckillOrderDeadListener {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.SECKILL_ORDER_DEAD_QUEUE, durable = "true"),
            exchange = @Exchange(name = MqConstants.SECKILL_DLX_EXCHANGE, type = "direct"),
            key = MqConstants.SECKILL_ORDER_DEAD_ROUTING_KEY
    ))
    public void handleDeadOrder(VoucherOrder order) {
        Long voucherId = order.getVoucherId();
        Long userId = order.getUserId();
        log.error("秒杀订单进入死信，准备执行补偿：voucherId={}, userId={}", voucherId, userId);

        try {
            // 0. 补偿前置校验：订单已落库则跳过补偿，避免超卖
            //    createVoucherOrder 返回 false 有两义：①DB 已有订单 ②DB 扣库存失败
            //    情形①若仍回补库存 + 清去重标记 → 库存虚高且用户可重复下单
            boolean orderExists = voucherOrderService.query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .count() > 0;
            if (orderExists) {
                log.warn("死信补偿跳过：订单已落库，回补库存将导致超卖 voucherId={}, userId={}",
                        voucherId, userId);
                return;
            }

            // 1. 释放 Redis 预扣库存
            String stockKey = "seckill:{" + voucherId + "}:stock";
            stringRedisTemplate.opsForValue().increment(stockKey);
            log.info("死信补偿：已释放库存 key={}", stockKey);

            // 2. 删除去重标记（放在回补库存之后：先恢复库存再放开重试资格）
            String orderKey = "seckill:{" + voucherId + "}:order";
            stringRedisTemplate.opsForSet().remove(orderKey, userId.toString());
            log.info("死信补偿：已删除去重标记 key={}, userId={}", orderKey, userId);
        } catch (Exception e) {
            log.error("死信补偿执行失败 voucherId={}, userId={}", voucherId, userId, e);
            // 补偿失败也不能抛异常（死信队列不应无限重试），记录日志等待人工处理
        }
    }
}
