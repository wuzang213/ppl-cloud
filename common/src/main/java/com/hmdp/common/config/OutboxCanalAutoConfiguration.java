package com.hmdp.common.config;

import com.hmdp.common.outbox.OutboxCanalHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.javatool.canal.client.handler.EntryHandler;

@Configuration
@ConditionalOnClass(EntryHandler.class)
public class OutboxCanalAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OutboxCanalHandler outboxCanalHandler(com.hmdp.common.utils.RabbitMqHelper rabbitMqHelper,
                                                  org.springframework.jdbc.core.JdbcTemplate jdbcTemplate){
        return new OutboxCanalHandler(rabbitMqHelper, jdbcTemplate);
    }
}
