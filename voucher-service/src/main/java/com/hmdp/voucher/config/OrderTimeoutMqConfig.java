package com.hmdp.voucher.config;

import com.hmdp.common.constants.MqConstants;
import com.hmdp.voucher.constants.VoucherConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 订单超时延迟拓扑（TTL 死信队列，RabbitMQ 原生）：生产端 → ORDER_TIMEOUT_DELAY_QUEUE（x-message-ttl=15min，
 * 无消费者）→ TTL 到期死信转发 → ORDER_TIMEOUT_QUEUE（关单 + 回补库存）。setDelay 需 delayed 插件，普通 direct
 * 交换机会静默忽略 x-delay 并立即投递、导致刚下单就关单；本类只属于 voucher-service，勿放 common。 */
@Configuration
public class OrderTimeoutMqConfig {

    /** 订单超时延迟交换机（direct）：延迟入口，也是 TTL 到期后死信转发的目标。 */
    @Bean
    public DirectExchange orderTimeoutDelayExchange() {
        return new DirectExchange(MqConstants.ORDER_TIMEOUT_DELAY_EXCHANGE, true, false);
    }

    /** 订单超时延迟队列：消息停留 15 分钟，无消费者，TTL 到期作为死信转发。 */
    @Bean
    public Queue orderTimeoutDelayQueue() {
        return QueueBuilder.durable(MqConstants.ORDER_TIMEOUT_DELAY_QUEUE)
                .ttl(VoucherConstants.ORDER_TIMEOUT_DELAY_MS)                 // 队列级 TTL：15 分钟
                .deadLetterExchange(MqConstants.ORDER_TIMEOUT_DELAY_EXCHANGE) // 死信目标交换机
                .deadLetterRoutingKey(MqConstants.ORDER_TIMEOUT_ROUTING_KEY)  // 死信目标路由键
                .build();
    }

    /** 延迟队列 → 延迟交换机的绑定（routingKey=order.timeout.delay）。 */
    @Bean
    public Binding orderTimeoutDelayBinding(DirectExchange orderTimeoutDelayExchange,
                                            Queue orderTimeoutDelayQueue) {
        return BindingBuilder.bind(orderTimeoutDelayQueue)
                .to(orderTimeoutDelayExchange)
                .with(MqConstants.ORDER_TIMEOUT_DELAY_ROUTING_KEY);
    }

    /** 订单超时消费队列：只接收死信转发过来的消息（routingKey=order.timeout）。 */
    @Bean
    public Queue orderTimeoutQueue() {
        return QueueBuilder.durable(MqConstants.ORDER_TIMEOUT_QUEUE)
                .withArgument("x-queue-mode", "lazy")
                .build();
    }

    /** 消费队列 → 延迟交换机的绑定（routingKey=order.timeout，死信转发的落点）。 */
    @Bean
    public Binding orderTimeoutBinding(DirectExchange orderTimeoutDelayExchange,
                                       Queue orderTimeoutQueue) {
        return BindingBuilder.bind(orderTimeoutQueue)
                .to(orderTimeoutDelayExchange)
                .with(MqConstants.ORDER_TIMEOUT_ROUTING_KEY);
    }
}
