package com.hmdp.voucher.listener;

import com.hmdp.common.constants.MqConstants;
import com.hmdp.voucher.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/** 订单超时关闭消费者。延迟走「TTL 死信队列」（RabbitMQ 原生，不用 delayed 插件）：拓扑由
 * {@code OrderTimeoutMqConfig} 以 @Bean 声明，本类只消费最终落到 {@link MqConstants#ORDER_TIMEOUT_QUEUE} 的消息。
 * 链路：生产端 → 延迟队列（x-message-ttl=15min）→ TTL 到期死信转发 → 本队列 → 关单。 */
@Slf4j
@Component
public class OrderTimeoutListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /** 消费订单超时事件，关单并回补库存。队列/绑定由 OrderTimeoutMqConfig 声明，
     * 这里用 queues 引用、避免重复声明交换机。 */
    @RabbitListener(queues = MqConstants.ORDER_TIMEOUT_QUEUE)
    public void handleTimeout(Long orderId) {
        log.info("处理订单超时关闭，orderId={}", orderId);
        voucherOrderService.closeTimeoutOrder(orderId);
    }
}
