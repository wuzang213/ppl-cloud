package com.hmdp.common.outbox;

import cn.hutool.json.JSONUtil;
import com.hmdp.common.domain.OutboxEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Outbox 写入器：业务表和事件表必须在同一本地事务中提交。所有事件统一以 OutboxEvent 信封落库，
 * 由 Canal 监听 business_outbox 后交给 OutboxDispatcher 投递到 MQ；相比事务后直接操作 Redis/发 MQ，
 * outbox 记录可对账重试，不会因进程异常而丢失副作用。 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final JdbcTemplate jdbcTemplate;

    /** 当前服务名，写入事件信封用于 Canal 消费端校验归属：四库共用 canal instance 时 outbox 主键会跨库撞车，
     * 该字段是客户端 filter 配漏时的最后一道防线。 */
    @Value("${spring.application.name:}")
    private String app;

    /**
     * 写入 outbox 事件（默认广播到缓存同步 fanout 交换机，各服务清本地缓存）。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String aggregateType, Long aggregateId, String eventType, Object body) {
        write(aggregateType, aggregateId, eventType, null, null, null, body);
    }

    /**
     * 写入 outbox 事件，指定目标交换机与路由键。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String aggregateType, Long aggregateId, String eventType,
                      String exchange, String routingKey, Object body) {
        write(aggregateType, aggregateId, eventType, exchange, routingKey, null, body);
    }

    /**
     * 写入 outbox 事件，指定目标交换机、路由键与延迟时间。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String aggregateType, Long aggregateId, String eventType,
                      String exchange, String routingKey, Integer delayMs, Object body) {
        OutboxEvent event = new OutboxEvent(app, exchange, routingKey, delayMs, body);
        jdbcTemplate.update("""
                INSERT INTO business_outbox(
                    aggregate_type, aggregate_id, event_type, payload,
                    status, retry_count, next_retry_time, created_time, updated_time)
                VALUES (?, ?, ?, ?, 0, 0, NOW(), NOW(), NOW())
                """, aggregateType, aggregateId, eventType, JSONUtil.toJsonStr(event));
    }
}
