package com.hmdp.search.task;

import com.hmdp.search.config.SearchProperties;
import com.hmdp.search.dao.SearchStateDao;
import com.hmdp.search.domain.SearchFailedRow;
import com.hmdp.search.service.ISearchIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** 索引失败重试任务。数据来源是 {@code search_index_failed} 表而不是 MQ：失败落到有主键的表里，重试才有明确的进度与上限，也能看出哪些一直修不好、需人工介入（靠 MQ 重投则次数散落在 broker header 里，上限到了要么进死信黑洞、要么无限 requeue 打满 CPU）。
 * 退避策略 {@code 30s × (retryCount + 1)}，超 {@code hmdp.search.failed-retry-max}（默认 5）次转 {@code FAILED} 终态并告警，与 outbox 的 {@code MAX_RETRY = 5} 对齐。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchFailedRetryTask {

    private static final int BATCH_SIZE = 200;

    private final SearchStateDao stateDao;

    private final ISearchIndexService indexService;

    private final SearchProperties properties;

    @Scheduled(fixedDelayString = "${hmdp.search.retry-interval-millis:60000}")
    public void retryFailed() {
        List<SearchFailedRow> rows;
        try {
            rows = stateDao.findPending(BATCH_SIZE);
        } catch (Exception e) {
            log.error("扫描待重试索引失败记录异常", e);
            return;
        }
        if (rows.isEmpty()) {
            return;
        }
        log.info("开始重试索引失败记录，共 {} 条", rows.size());
        for (SearchFailedRow row : rows) {
            retryOne(row);
        }
    }

    private void retryOne(SearchFailedRow row) {
        try {
            // 重新以业务库真值为准刷一次索引；文档已不存在时会自然走删除分支
            indexService.apply(row.getIndexType(), row.getBizId());
            stateDao.markFailedSuccess(row.getId());
            log.info("索引失败记录已修复 id={}, type={}, bizId={}",
                    row.getId(), row.getIndexType(), row.getBizId());
        } catch (Exception e) {
            int nextRetry = row.getRetryCount() + 1;
            if (nextRetry >= properties.getFailedRetryMax()) {
                stateDao.markFailedTerminal(row.getId(), e.getMessage());
                log.error("索引失败记录重试耗尽，转终态失败，需人工介入 id={}, type={}, bizId={}, eventType={}",
                        row.getId(), row.getIndexType(), row.getBizId(), row.getEventType(), e);
            } else {
                stateDao.markFailedRetry(row.getId(), row.getRetryCount());
                log.warn("索引失败记录第 {} 次重试仍失败，稍后再试 id={}, type={}, bizId={}",
                        nextRetry, row.getId(), row.getIndexType(), row.getBizId(), e);
            }
        }
    }
}
