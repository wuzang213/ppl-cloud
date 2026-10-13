package com.hmdp.search.config;

import com.hmdp.common.constants.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 搜索索引同步的死信基建：死信交换机与死信队列必须在这里显式声明成 Bean，否则队列声明引用死信交换机时会 PRECONDITION_FAILED。
 * 死信不 requeue 是硬规则（requeue=true 会让坏消息无限重投打满 CPU）；失败落 {@code search_index_failed} 表才是重试依据。 */
@Configuration
public class SearchMqConfig {

    @Bean
    public DirectExchange searchIndexDlxExchange() {
        return new DirectExchange(MqConstants.SEARCH_INDEX_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue searchIndexDeadQueue() {
        return QueueBuilder.durable(MqConstants.SEARCH_INDEX_DEAD_QUEUE)
                .lazy()
                .build();
    }

    @Bean
    public Binding searchIndexDeadBinding(Queue searchIndexDeadQueue, DirectExchange searchIndexDlxExchange) {
        return BindingBuilder.bind(searchIndexDeadQueue)
                .to(searchIndexDlxExchange)
                .with(MqConstants.SEARCH_INDEX_DEAD_ROUTING_KEY);
    }
}
