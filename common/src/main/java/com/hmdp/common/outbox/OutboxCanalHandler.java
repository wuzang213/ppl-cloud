package com.hmdp.common.outbox;

import cn.hutool.core.util.StrUtil;
import com.hmdp.common.domain.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import top.javatool.canal.client.annotation.CanalTable;
import top.javatool.canal.client.handler.EntryHandler;

/**
 * Canal 监听 {@code business_outbox} 表，将事件交给 {@link OutboxDispatcher} 投递到 MQ。
 * <p>
 * 状态机 0=待投递 / 1=投递中 / 2=已确认 / 3=终态失败，
 * confirm 回调驱动状态机；投递失败回退待投递并累计 retry_count。
 * <p>
 * 四个库部署在同一个 MySQL 实例、共用一个 canal instance 时，各库 {@code business_outbox.id}
 * 都从 1 自增会跨库撞车，正常由客户端 {@code canal.filter} 隔离。为避免 filter 配漏导致
 * 事件被误投或静默丢失，这里做两道防线：事件归属校验 + 条件认领。
 */
@Slf4j
@RequiredArgsConstructor
@CanalTable("business_outbox")
public class OutboxCanalHandler implements EntryHandler<OutboxRow> {

    private final OutboxDispatcher outboxDispatcher;

    /** 当前服务名，用于判断收到的 outbox 事件是否属于本服务的库 */
    private final String app;

    @Override
    public void insert(OutboxRow row) {
        if (row.getStatus() != null && row.getStatus() != 0) {
            return;
        }
        // 防线一：归属校验。canal 客户端 handler 只按表名路由、不含库名，
        // filter 配漏时会收到其它库的 business_outbox 事件，其主键可能与本库某条待投递
        // 记录撞车，进而投错内容、并把本库真正的事件挤掉。凭 payload 中的 app 直接丢弃。
        if (!isOwnEvent(row.getPayload())) {
            log.debug("丢弃非本服务的 outbox 事件，id={}, type={}, app={}",
                    row.getId(), row.getEventType(), app);
            return;
        }
        try {
            // 防线二：条件认领（仅 status=0 才置为投递中）。
            // canal 重投同一批 insert 时状态已非 0，此处直接跳过，避免重复投递
            // confirm 回调负责转已确认 / 回退待投递
            if (!outboxDispatcher.markDispatching(row.getId())) {
                log.debug("outbox 事件已被处理，跳过重复投递 id={}", row.getId());
                return;
            }
            outboxDispatcher.dispatch(row.getId(), row.getPayload());
        } catch (Exception e) {
            log.error("outbox 消息投递失败，id={}, type={}", row.getId(), row.getEventType(), e);
            outboxDispatcher.markRetry(row.getId());
        }
    }

    /**
     * 判断事件是否归属当前服务。
     * <p>
     * 历史数据没有 app 字段（或 app 为空）时放行，保持兼容。
     */
    private boolean isOwnEvent(String payload) {
        if (StrUtil.isBlank(app)) {
            // 当前服务名未知，不做归属判断，退化为仅靠客户端 filter 隔离
            return true;
        }
        OutboxEvent event = outboxDispatcher.parse(payload);
        return StrUtil.isBlank(event.getApp()) || app.equals(event.getApp());
    }

    @Override
    public void update(OutboxRow before, OutboxRow after) {
        // 对账任务负责扫描重投，这里不重复处理
    }

    @Override
    public void delete(OutboxRow row) {
        // 清理事件不参与广播
    }
}
