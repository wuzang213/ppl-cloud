package com.hmdp.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Kafka 消费失败处理：关闭自动提交后（enable-auto-commit: false + ack-mode: manual_immediate），毒消息不 ack 会被反复 seek 重放堵死分区。
 * 故间隔 1s 重试 3 次，耗尽后打 error 日志 + setCommitRecovered(true) 提交 offset 跳过；⚠️ setCommitRecovered 仅在 ackMode=MANUAL_IMMEDIATE 时生效，两者必须成对配置。 */
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
