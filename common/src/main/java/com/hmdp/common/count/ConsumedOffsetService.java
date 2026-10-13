package com.hmdp.common.count;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Kafka 消费幂等：以 (topic, partition, offset) 为唯一键只放行一次 —— offset 天然唯一且消费端可读，无需生产端生成 messageId。
 * 必须与「累加类业务写」放在同一本地事务，业务写失败一起回滚，重投才不会被幂等表误挡；建表脚本见 sql/consumed_offset.sql。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumedOffsetService {

    /** 列名 partition 是 MySQL 保留字、offset 是关键字，必须加反引号，否则部分版本报语法错误。 */
    private static final String INSERT_SQL = """
            INSERT IGNORE INTO consumed_offset(topic, `partition`, `offset`, created_time)
            VALUES (?, ?, ?, NOW())
            """;

    private final JdbcTemplate jdbcTemplate;

    /** 标记这条 Kafka 消息为已消费：true=首次处理（调用方继续累加业务写），false=重复消息（必须跳过业务写）。
     * 异常时返回 true 放行（与 OutboxConsumeService#tryMarkConsumed 同口径），免幂等表不可用时浏览量整体停摆；依赖 InnoDB 单条语句失败只回滚该语句。 */
    public boolean tryMarkConsumed(String topic, int partition, long offset) {
        try {
            int inserted = jdbcTemplate.update(INSERT_SQL, topic, partition, offset);
            return inserted > 0;
        } catch (Exception e) {
            log.error("Kafka 消费幂等记录失败，已放行本条消息（幂等能力暂时失效，可能重复累加）"
                    + " topic={}, partition={}, offset={}", topic, partition, offset, e);
            return true;
        }
    }
}
