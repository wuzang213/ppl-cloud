package com.hmdp.search.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 投到死信交换机的失败信封，只用于可观测：真正的失败台账是 {@code search_index_failed} 表（重试任务凭它工作）。
 * 往死信队列投一份，是让运维能在 RabbitMQ 控制台直接看到哪些消息写 ES 失败了，不用翻库。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchFailEnvelope implements Serializable {

    private static final long serialVersionUID = 1L;

    private String indexType;

    private Long bizId;

    private String eventType;

    private String error;
}
