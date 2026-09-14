package com.hmdp.voucher.listener;

import com.hmdp.common.constants.MqConstants;
import com.hmdp.voucher.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 订单超时关闭消费者。
 * <p>
 * 延迟实现采用「TTL 死信队列」方案（RabbitMQ 原生，不依赖 delayed_message_exchange 插件）：
 * 拓扑（延迟交换机 / 延迟队列 / 消费队列 / 绑定）由 {@code OrderTimeoutMqConfig} 以 @Bean 声明，
 * 本类只负责消费最终落到 {@link MqConstants#ORDER_TIMEOUT_QUEUE} 的消息。
 * <p>
 * 完整链路：生产端发到延迟队列（x-message-ttl=15min）→ TTL 到期死信转发 → 本队列 → 关单。
 */
@Slf4j
@Component
public class OrderTimeoutListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 消费订单超时事件，关单并回补库存。
     * 队列/绑定由 OrderTimeoutMqConfig 声明，这里用 queues 引用、避免重复声明交换机。
     */
    @RabbitListener(queues = MqConstants.ORDER_TIMEOUT_QUEUE)
    public void handleTimeout(Long orderId) {
        log.info("处理订单超时关闭，orderId={}", orderId);
        voucherOrderService.closeTimeoutOrder(orderId);
    }
}
