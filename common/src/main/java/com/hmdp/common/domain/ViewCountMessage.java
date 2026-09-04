package com.hmdp.common.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kafka 浏览量批量聚合消息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ViewCountMessage {

    private String bizType;

    private Long bizId;

    private Long count;
}
