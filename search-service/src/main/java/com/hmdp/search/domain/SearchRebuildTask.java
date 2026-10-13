package com.hmdp.search.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code search_rebuild_task} 的一行：全量重建任务的进度与状态 */
@Data
public class SearchRebuildTask {

    private Long id;

    private String indexType;

    private String targetIndex;

    private String aliasName;

    /** RUNNING / SUCCESS / FAILED */
    private String status;

    private Long total;

    private Long processed;

    private LocalDateTime snapshotStart;

    private String errorMsg;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
