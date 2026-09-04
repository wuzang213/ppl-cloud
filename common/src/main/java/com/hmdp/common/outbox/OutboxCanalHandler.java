package com.hmdp.common.outbox;

import com.hmdp.common.constants.CacheConstants;
import com.hmdp.common.constants.MqConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.utils.RabbitMqHelper;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.javatool.canal.client.annotation.CanalTable;
import top.javatool.canal.client.handler.EntryHandler;

/**
 * Canal 监听 outbox 表，将事件投递到 RabbitMQ fanout 广播交换机。
 */
@Slf4j
@RequiredArgsConstructor
@CanalTable("business_outbox")
public class OutboxCanalHandler implements EntryHandler<OutboxRow> {

    private final RabbitMqHelper rabbitMqHelper;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void insert(OutboxRow row) {
        if (row.getStatus() != null && row.getStatus() != 0) {
            return;
        }
        CacheSyncMessage message = JSONUtil.toBean(row.getPayload(), CacheSyncMessage.class);
        try {
            rabbitMqHelper.sendMessageWithConfirm(
                    CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE,
                    "",
                    message,
                    MqConstants.MQ_RETRY_TIMES);
            jdbcTemplate.update("""
                    UPDATE business_outbox
                    SET status = 1, updated_time = NOW()
                    WHERE id = ?
                    """, row.getId());
        } catch (Exception e) {
            log.error("outbox 消息投递失败，id={}, type={}", row.getId(), row.getEventType(), e);
        }
    }

    @Override
    public void update(OutboxRow before, OutboxRow after) {
        // 对账任务负责扫描重投，这里不重复处理
    }

    @Override
    public void delete(OutboxRow row) {
        // 清理事件不参与缓存广播
    }
}
