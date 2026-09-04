package com.hmdp.common.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@ConditionalOnProperty(
        prefix = "spring.rabbitmq.listener.simple.retry",
        name = "enabled",
        havingValue = "true"
)
public class MqConsumeErrorAutoConfiguration {

    @Value("${spring.application.name}")
    private String serviceName;

    @Bean
    public DirectExchange errorDirectExchange() {
        return ExchangeBuilder.directExchange("error.direct")
                .build();
    }

    @Bean
    public Queue errorQueue() {
        return QueueBuilder
                .durable(serviceName + ".error.queue")
                .build();
    }

    @Bean
    public Binding errorBinding() {
        return BindingBuilder.bind(errorQueue())
                .to(errorDirectExchange())
                .with(serviceName);
    }

    /**
     * 创建消息重发工具
     *
     * @param rabbitTemplate
     * @return
     */
    @Bean
    public RepublishMessageRecoverer republishMessageRecoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate, "error.direct", serviceName);
    }
}