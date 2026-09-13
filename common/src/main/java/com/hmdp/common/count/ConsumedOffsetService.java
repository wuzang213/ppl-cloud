package com.hmdp.common.count;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka 消费幂等：以 (topic, partition, offset) 为唯一键，同一条消息只放行一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumedOffsetService {

    /**
     * 注意：列名 {@code partition} 是 MySQL 保留字、{@code offset} 是关键字，
     * 必须加反引号，否则部分版本会直接报语法错误。
     */
    private static final String INSERT_SQL = """
            INSERT IGNORE INTO consumed_offset(topic, `partition`, `offset`, created_time)
            VALUES (?, ?, ?, NOW())
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 尝试把「这条 Kafka 消息」标记为已消费。
     * <p>
     * <b>异常时返回 {@code true}（放行）</b>，与 {@code OutboxConsumeService#tryMarkConsumed}
     * 保持同一口径，理由是失败模式更可控：
     * <ul>
     *   <li>放行 → 幂等表不可用期间退化成「改造前的行为」（累加照做，极端情况会重复累加），
     *       并刷一条 error 日志，浏览量统计不会整体停摆；</li>
     *   <li>若改为向外抛异常 → 每条消息都在 INSERT 处失败，容器重试耗尽后逐条跳过，
     *       结果是<b>浏览量全线停止更新</b>，故障面大得多。</li>
     * </ul>
     * 这里依赖 MySQL/InnoDB 的语义：单条语句失败只回滚该语句，不会中止整个事务，
     * 因此 catch 之后同事务内的 UPDATE 仍然可以正常提交（PostgreSQL 下不成立，本项目只用 MySQL）。
     *
     * @return {@code true} = 首次处理（调用方应继续执行累加类业务写）；
     *         {@code false} = 重复消息（调用方必须直接跳过，不做任何业务写）
     */
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
