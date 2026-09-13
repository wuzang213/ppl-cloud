package com.hmdp.common.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outbox 事件信封。
 * <p>
 * {@code business_outbox.payload} 统一采用本结构，{@code OutboxDispatcher} 解析后
 * 按 exchange / routingKey 投递到 MQ；delayMs &gt; 0 时按延迟消息投递。
 * <ul>
 *   <li>exchange 为空 → 默认广播到缓存同步 fanout 交换机（各服务清本地缓存）</li>
 *   <li>routingKey 为空 → 使用空路由键（fanout 忽略路由键）</li>
 *   <li>body 为真正的业务消息体，可为 CacheSyncMessage / 业务 DTO / 简单类型</li>
 * </ul>
 * {@code app} 记录事件归属服务，供 Canal 消费端校验：四个库在同一个 MySQL 实例下
 * 共用一个 canal instance 时，各库 {@code business_outbox.id} 都从 1 自增、会跨库撞车，
 * 正常情况下由客户端 filter 隔离；一旦 filter 配漏，消费端可凭 app 直接丢弃非本服务事件
 * （历史数据无 app 字段，放行以保持兼容）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    /** 事件归属服务（spring.application.name），Canal 消费端据此校验是否为本服务事件 */
    private String app;

    /** 目标交换机，为空时默认走缓存同步 fanout 广播 */
    private String exchange;

    /** 路由键，为空时使用 "" */
    private String routingKey;

    /** 延迟毫秒数，大于 0 时按延迟消息投递 */
    private Integer delayMs;

    /** 业务消息体 */
    private Object body;
}
