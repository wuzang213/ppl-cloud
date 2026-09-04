package com.hmdp.common.outbox;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OutboxRow {

    private Long id;

    private String aggregateType;

    private Long aggregateId;

    private String eventType;

    private String payload;

    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
