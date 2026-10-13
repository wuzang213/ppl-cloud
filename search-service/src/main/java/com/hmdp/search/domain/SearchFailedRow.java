package com.hmdp.search.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code search_index_failed} 的一行：写索引失败后待重试的记录 */
@Data
public class SearchFailedRow {

    private Long id;

    private String indexType;

    private Long bizId;

    private String eventType;

    private String errorMsg;

    private Integer retryCount;

    private String status;

    private LocalDateTime nextRetryTime;
}
