package com.hmdp.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 消费幂等：以 MQ messageId 为唯一键，重复消息直接忽略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxConsumeService {

    private final JdbcTemplate jdbcTemplate;

    public boolean tryMarkConsumed(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return true;
        }
        try {
            int updated = jdbcTemplate.update("""
                    INSERT IGNORE INTO outbox_consume_record(message_id, created_time)
                    VALUES (?, NOW())
                    """, messageId);
            return updated > 0;
        } catch (Exception e) {
            log.error("outbox 消费幂等记录失败，messageId={}", messageId, e);
            return true;
        }
    }
}
