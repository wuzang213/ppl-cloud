package com.hmdp.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.common.utils.RabbitMqHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
// @ConditionalOnClass({MessageConverter.class, RabbitTemplate.class})
public class MqConfig {

    @Bean
    // @ConditionalOnBean({RabbitTemplate.class, ObjectMapper.class})
    public MessageConverter messageConverter(ObjectMapper mapper) {
        Jackson2JsonMessageConverter jackson2JsonMessageConverter = new Jackson2JsonMessageConverter(mapper);
        jackson2JsonMessageConverter.setCreateMessageIds(true);
        return jackson2JsonMessageConverter;
    }

    @Bean
    // @ConditionalOnBean(RabbitTemplate.class)
    public RabbitMqHelper rabbitMqHelper(RabbitTemplate rabbitTemplate) {
        rabbitTemplate.setReturnCallback((message, replyCode, replyText, exchange, routingKey) -> {
            log.error("触发return callback");
            log.debug("exchange: {}", exchange);
            log.debug("routingKey: {}", routingKey);
            log.debug("message: {}", message);
            log.debug("replyCode: {}", replyCode);
            log.debug("replyText: {}", replyText);
        });
        return new RabbitMqHelper(rabbitTemplate);
    }
}