package com.hmdp.common.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outbox 事件信封：business_outbox.payload 统一采用本结构，OutboxDispatcher 按 exchange/routingKey 投递给 MQ
 *（exchange 为空走缓存同步 fanout，routingKey 为空用 ""，delayMs>0 按延迟消息）；body 为业务消息体。
 * app 记录事件归属服务：四库共用一个 canal instance 时 business_outbox.id 跨库撞车，filter 配漏时凭 app 丢弃非本服务事件（历史无 app 放行）。 */
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
