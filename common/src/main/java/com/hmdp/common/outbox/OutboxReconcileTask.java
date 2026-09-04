package com.hmdp.common.outbox;

import com.hmdp.common.constants.CacheConstants;
import com.hmdp.common.constants.MqConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.utils.RabbitMqHelper;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Outbox 对账补偿：把长时间未确认投递的消息重新扫描投递。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxReconcileTask {

    private final JdbcTemplate jdbcTemplate;

    private final RabbitMqHelper rabbitMqHelper;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void republish() {
        try {
            // 已尝试但长时间没有确认的消息，重置为待投递
            jdbcTemplate.update("""
                    UPDATE business_outbox
                    SET status = 0,
                        retry_count = 0,
                        next_retry_time = NOW()
                    WHERE status = 1
                      AND updated_time < DATE_SUB(NOW(), INTERVAL 5 MINUTE)
                    """);

            List<Map<String, Object>> pending = jdbcTemplate.queryForList("""
                    SELECT id, payload
                    FROM business_outbox
                    WHERE status = 0
                      AND retry_count < 5
                      AND next_retry_time <= NOW()
                    ORDER BY id
                    LIMIT 100
                    """);
            for (Map<String, Object> row : pending) {
                Long id = ((Number) row.get("id")).longValue();
                CacheSyncMessage message = JSONUtil.toBean(
                        String.valueOf(row.get("payload")), CacheSyncMessage.class);
                rabbitMqHelper.sendMessageWithConfirm(
                        CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE,
                        "",
                        message,
                        MqConstants.MQ_RETRY_TIMES);
                jdbcTemplate.update("""
                        UPDATE business_outbox
                        SET status = 1,
                            retry_count = retry_count + 1,
                            next_retry_time = DATE_ADD(NOW(), INTERVAL 30 SECOND),
                            updated_time = NOW()
                        WHERE id = ?
                        """, id);
            }
        } catch (Exception e) {
            log.error("outbox 对账补偿任务执行失败", e);
        }
    }
}
