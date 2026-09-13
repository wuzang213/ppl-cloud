package com.hmdp.common.config;

import com.hmdp.common.outbox.OutboxCanalHandler;
import com.hmdp.common.outbox.OutboxDispatcher;
import org.springframework.beans.factory.annotation.Value;
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
    public OutboxCanalHandler outboxCanalHandler(OutboxDispatcher outboxDispatcher,
                                                 @Value("${spring.application.name:}") String app) {
        return new OutboxCanalHandler(outboxDispatcher, app);
    }
}
