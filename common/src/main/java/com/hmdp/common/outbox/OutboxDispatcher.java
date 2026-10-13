package com.hmdp.common.outbox;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.common.constants.CacheConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.domain.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Outbox 事件投递器：把 business_outbox 一行的 payload 投到目标 MQ 并驱动状态机
 * （ack → status=2；nack/异常 → status=0 且 retry_count+1；retry_count >= MAX_RETRY(5) → status=3 终态失败）；
 * 投递前统一移除 __TypeId__ header，让消费端按监听方法签名反序列化，避免跨服务类型不一致转换失败。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {

    /** 超过该重试次数标记为终态失败 */
    public static final int MAX_RETRY = 5;

    /** Spring AMQP Jackson 消息类型 header，投递前移除 */
    private static final String TYPE_ID_HEADER = "__TypeId__";

    private final RabbitTemplate rabbitTemplate;

    private final JdbcTemplate jdbcTemplate;

    /** 投递一条 outbox 事件，调用方需先把 status 置为 1（投递中）。
     * @param id outbox 记录 id；@param payloadJson payload（OutboxEvent 信封 JSON） */
    public void dispatch(Long id, String payloadJson) {
        OutboxEvent event = parse(payloadJson);
        String exchange = StrUtil.blankToDefault(event.getExchange(), CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE);
        String routingKey = StrUtil.nullToEmpty(event.getRoutingKey());
        Object body = event.getBody();

        CorrelationData cd = new CorrelationData("outbox:" + id);
        cd.getFuture().addCallback(
                result -> {
                    if (result != null && result.isAck()) {
                        // ack → 已确认
                        jdbcTemplate.update(
                                "UPDATE business_outbox SET status = 2, updated_time = NOW() WHERE id = ?", id);
                    } else {
                        log.warn("outbox 消息收到 NACK，id={}", id);
                        markRetry(id);
                    }
                },
                ex -> {
                    log.error("outbox confirm 回调异常，id={}", id, ex);
                    markRetry(id);
                }
        );

        Integer delayMs = event.getDelayMs();
        if (delayMs != null && delayMs > 0) {
            // ⚠️ setDelay 依赖 RabbitMQ delayed_message_exchange 插件且交换机须为 x-delayed-message 类型，
            // 否则 x-delay header 被静默忽略、消息立即投递（延迟失效）。订单超时已改用 TTL 死信队列
            //（voucher 的 OrderTimeoutMqConfig），此处仅兼容历史事件并打告警。
            log.warn("outbox 事件带 delayMs={}，走 setDelay 延迟；若目标交换机 {} 非 x-delayed-message 类型，"
                    + "延迟将静默失效（消息立即投递）。建议改用 TTL 死信队列方案。", delayMs, exchange);
        }
        MessagePostProcessor postProcessor = message -> {
            MessageProperties props = message.getMessageProperties();
            props.getHeaders().remove(TYPE_ID_HEADER);
            // 消费端以 messageId 作为幂等唯一键（OutboxConsumeService.tryMarkConsumed），
            // 必须显式写入：Spring AMQP 不会自动填充 messageId，
            // 缺失会导致消费端幂等判断恒为 true、outbox_consume_record 永远为空。
            props.setMessageId("outbox:" + id);
            if (delayMs != null && delayMs > 0) {
                props.setDelay(delayMs);
            }
            return message;
        };
        rabbitTemplate.convertAndSend(exchange, routingKey, body, postProcessor, cd);
    }

    /** 条件认领：仅 status=0（待投递）才置为 status=1（投递中），true=认领成功应继续投递、false=已被处理应跳过。
     * 必须带 status 条件，否则 canal 重投或其它库 outbox 主键撞车会把已确认记录改回投递中，对账任务再也扫不到它（事件静默丢失）。 */
    public boolean markDispatching(Long id) {
        return jdbcTemplate.update(
                "UPDATE business_outbox SET status = 1, updated_time = NOW() WHERE id = ? AND status = 0", id) > 0;
    }

    /**
     * 回退为待投递，retry_count+1，达上限转终态失败并告警。
     */
    public void markRetry(Long id) {
        jdbcTemplate.update("""
                UPDATE business_outbox
                SET status = 0,
                    retry_count = retry_count + 1,
                    next_retry_time = DATE_ADD(NOW(), INTERVAL 30 SECOND),
                    updated_time = NOW()
                WHERE id = ?
                """, id);
        int terminal = jdbcTemplate.update("""
                UPDATE business_outbox
                SET status = 3, updated_time = NOW()
                WHERE id = ? AND retry_count >= ? AND status = 0
                """, id, MAX_RETRY);
        if (terminal > 0) {
            log.error("outbox 消息重试耗尽，标记为终态失败 id={}", id);
        } else {
            log.warn("outbox 消息投递失败，已回退待重试 id={}", id);
        }
    }

    /** 解析 payload：统一为 OutboxEvent 信封；兼容历史裸消息（整体作为 CacheSyncMessage 广播）。
     * 对 OutboxCanalHandler 开放，便于投递前先校验事件归属。 */
    public OutboxEvent parse(String payloadJson) {
        OutboxEvent event = JSONUtil.toBean(payloadJson, OutboxEvent.class);
        if (event.getBody() == null && event.getExchange() == null
                && event.getRoutingKey() == null && event.getDelayMs() == null) {
            // 兼容未包装的历史数据：整个 payload 就是 CacheSyncMessage
            return new OutboxEvent(null, null, null, null,
                    JSONUtil.toBean(payloadJson, CacheSyncMessage.class));
        }
        return event;
    }
}
