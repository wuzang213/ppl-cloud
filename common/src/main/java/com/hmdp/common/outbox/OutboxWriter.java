package com.hmdp.common.outbox;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 写入器：业务表和事件表必须在同一本地事务中提交。
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String aggregateType, Long aggregateId,
                      String eventType, Object payload) {
        jdbcTemplate.update("""
                INSERT INTO business_outbox(
                    aggregate_type, aggregate_id, event_type, payload,
                    status, retry_count, next_retry_time, created_time, updated_time)
                VALUES (?, ?, ?, ?, 0, 0, NOW(), NOW(), NOW())
                """, aggregateType, aggregateId, eventType, JSONUtil.toJsonStr(payload));
    }
}
