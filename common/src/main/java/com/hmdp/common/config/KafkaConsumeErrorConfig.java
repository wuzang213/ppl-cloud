package com.hmdp.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 消费失败处理：有界重试 + 重试耗尽后跳过并告警。
 */
@Slf4j
@Configuration
@ConditionalOnClass(DefaultErrorHandler.class)
public class KafkaConsumeErrorConfig {

    private static final long RETRY_INTERVAL_MS = 1_000L;

    private static final long MAX_RETRY_ATTEMPTS = 3L;

    @Bean
    public DefaultErrorHandler kafkaConsumeErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, ex) -> log.error(
                        "Kafka 消息重试 {} 次仍失败，跳过该消息以免阻塞分区: topic={}, partition={}, offset={}",
                        MAX_RETRY_ATTEMPTS, record.topic(), record.partition(), record.offset(), ex),
                new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRY_ATTEMPTS));
        handler.setCommitRecovered(true);
        return handler;
    }
}
