package com.hmdp.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Outbox 对账补偿：把长时间未确认投递的消息重新扫描投递。
 * <p>
 * 状态机 0=待投递 / 1=投递中 / 2=已确认 / 3=终态失败。
 * 对账只重置 status=1 超时未确认的（不重置 retry_count）；
 * retry_count 达上限的转 status=3（终态失败）+ 告警。
 * 投递与状态机驱动统一交给 {@link OutboxDispatcher}，与 Canal 首投逻辑保持一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxReconcileTask {

    private final JdbcTemplate jdbcTemplate;

    private final OutboxDispatcher outboxDispatcher;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void republish() {
        try {
            // 已投递中但长时间没有确认的消息，重置为待投递（不重置 retry_count）
            jdbcTemplate.update("""
                    UPDATE business_outbox
                    SET status = 0, next_retry_time = NOW()
                    WHERE status = 1
                      AND updated_time < DATE_SUB(NOW(), INTERVAL 5 MINUTE)
                    """);

            // retry_count 达上限 → 终态失败 + 告警
            List<Map<String, Object>> exhausted = jdbcTemplate.queryForList("""
                    SELECT id FROM business_outbox
                    WHERE status = 0 AND retry_count >= ?
                    """, OutboxDispatcher.MAX_RETRY);
            for (Map<String, Object> row : exhausted) {
                Long id = ((Number) row.get("id")).longValue();
                jdbcTemplate.update(
                        "UPDATE business_outbox SET status = 3, updated_time = NOW() WHERE id = ?", id);
                log.error("outbox 消息重试耗尽，标记为终态失败 id={}", id);
            }

            // 重新投递待投递消息
            List<Map<String, Object>> pending = jdbcTemplate.queryForList("""
                    SELECT id, payload
                    FROM business_outbox
                    WHERE status = 0
                      AND retry_count < ?
                      AND next_retry_time <= NOW()
                    ORDER BY id
                    LIMIT 100
                    """, OutboxDispatcher.MAX_RETRY);
            for (Map<String, Object> row : pending) {
                Long id = ((Number) row.get("id")).longValue();
                try {
                    // 条件认领，与 Canal 首投共用同一套语义；认领失败说明状态已被推进，跳过
                    if (!outboxDispatcher.markDispatching(id)) {
                        continue;
                    }
                    outboxDispatcher.dispatch(id, String.valueOf(row.get("payload")));
                } catch (Exception e) {
                    log.error("outbox 对账重投失败，id={}", id, e);
                    outboxDispatcher.markRetry(id);
                }
            }
        } catch (Exception e) {
            log.error("outbox 对账补偿任务执行失败", e);
        }
    }

    /**
     * 定时清理幂等表和已确认的 outbox 记录，防止无限增长。
     */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 60_000)
    public void cleanup() {
        try {
            // 清理 30 天前的幂等记录
            int n = jdbcTemplate.update(
                    "DELETE FROM outbox_consume_record WHERE created_time < DATE_SUB(NOW(), INTERVAL 30 DAY)");
            log.info("清理 outbox_consume_record 完成，删除 {} 条", n);
            // 清理已确认的 outbox 记录（status=2，7 天前）
            int m = jdbcTemplate.update(
                    "DELETE FROM business_outbox WHERE status = 2 AND updated_time < DATE_SUB(NOW(), INTERVAL 7 DAY)");
            log.info("清理 business_outbox 已确认记录完成，删除 {} 条", m);
        } catch (Exception e) {
            log.error("outbox 清理任务失败", e);
        }
        // Kafka 消费幂等表单独 try：它与 outbox 无依赖关系，且建表脚本可能还没在所有库执行，
        // 混在同一个 try 里会让它的异常把上面的 outbox 清理一起带崩（反之亦然）。
        try {
            int k = jdbcTemplate.update(
                    "DELETE FROM consumed_offset WHERE created_time < DATE_SUB(NOW(), INTERVAL 30 DAY)");
            if (k > 0) {
                log.info("清理 consumed_offset 完成，删除 {} 条", k);
            }
        } catch (Exception e) {
            log.error("Kafka 消费幂等表清理失败（若表尚未创建请执行 sql/consumed_offset.sql）", e);
        }
    }
}
