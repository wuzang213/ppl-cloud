package com.hmdp.search.dao;

import com.hmdp.search.domain.SearchFailedRow;
import com.hmdp.search.domain.SearchRebuildTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/** 重建进度表与失败表的数据访问（JdbcTemplate，不建表 —— DDL 唯一权威是 sql/search_service.sql）。
 * ⚠️ 所有时间比较都交给数据库 NOW()：本实例 {@code time_zone = SYSTEM} 即 UTC，而 {@code LocalDateTime.now()} 是 UTC+8 墙钟，混用会精确差 8 小时，故 {@code next_retry_time} 一律用 {@code DATE_ADD(NOW(), INTERVAL ? SECOND)}。 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SearchStateDao {

    private final JdbcTemplate jdbcTemplate;

    // ==================== search_rebuild_task ====================

    /** 新建一次重建任务。{@code snapshot_start} 改用事件驱动追平（见 {@link com.hmdp.search.service.RebuildRegistry}）后已不参与比较，仅是本次重建起点的审计信息。
     * 故一律用 DB 的 {@code NOW()}：省一次往返，也避免 {@code Timestamp.valueOf(LocalDateTime)} 用 JVM 本地时区重新解释 DB 的 UTC 值（差 8 小时）。 */
    public long insertRebuildTask(String indexType, String targetIndex, String aliasName) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO search_rebuild_task(
                        index_type, target_index, alias_name, status, total, processed,
                        snapshot_start, started_at)
                    VALUES (?, ?, ?, 'RUNNING', 0, 0, NOW(), NOW())
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, indexType);
            ps.setString(2, targetIndex);
            ps.setString(3, aliasName);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    public void updateRebuildProgress(long taskId, long processed, long total) {
        jdbcTemplate.update("""
                UPDATE search_rebuild_task SET processed = ?, total = ? WHERE id = ?
                """, processed, total, taskId);
    }

    public void finishRebuildTask(long taskId, String status, long processed, String errorMsg) {
        jdbcTemplate.update("""
                UPDATE search_rebuild_task
                SET status = ?, processed = ?, error_msg = ?, finished_at = NOW()
                WHERE id = ?
                """, status, processed, truncate(errorMsg), taskId);
    }

    public SearchRebuildTask findLatestRebuildTask(String indexType) {
        List<SearchRebuildTask> list = jdbcTemplate.query("""
                SELECT id, index_type, target_index, alias_name, status, total, processed,
                       snapshot_start, error_msg, started_at, finished_at
                FROM search_rebuild_task
                WHERE index_type = ?
                ORDER BY id DESC LIMIT 1
                """, (rs, rowNum) -> {
            SearchRebuildTask task = new SearchRebuildTask();
            task.setId(rs.getLong("id"));
            task.setIndexType(rs.getString("index_type"));
            task.setTargetIndex(rs.getString("target_index"));
            task.setAliasName(rs.getString("alias_name"));
            task.setStatus(rs.getString("status"));
            task.setTotal(rs.getLong("total"));
            task.setProcessed(rs.getLong("processed"));
            task.setSnapshotStart(rs.getTimestamp("snapshot_start") == null
                    ? null : rs.getTimestamp("snapshot_start").toLocalDateTime());
            task.setErrorMsg(rs.getString("error_msg"));
            task.setStartedAt(rs.getTimestamp("started_at") == null
                    ? null : rs.getTimestamp("started_at").toLocalDateTime());
            task.setFinishedAt(rs.getTimestamp("finished_at") == null
                    ? null : rs.getTimestamp("finished_at").toLocalDateTime());
            return task;
        }, indexType);
        return list.isEmpty() ? null : list.get(0);
    }

    // ==================== search_index_failed ====================

    public void insertFailed(String indexType, Long bizId, String eventType, String errorMsg) {
        jdbcTemplate.update("""
                INSERT INTO search_index_failed(
                    index_type, biz_id, event_type, error_msg, retry_count, status,
                    next_retry_time, created_time, updated_time)
                VALUES (?, ?, ?, ?, 0, 'PENDING', NOW(), NOW(), NOW())
                """, indexType, bizId, eventType, truncate(errorMsg));
    }

    public List<SearchFailedRow> findPending(int limit) {
        return jdbcTemplate.query("""
                SELECT id, index_type, biz_id, event_type, error_msg, retry_count, status, next_retry_time
                FROM search_index_failed
                WHERE status = 'PENDING' AND next_retry_time <= NOW()
                ORDER BY id LIMIT ?
                """, (rs, rowNum) -> {
            SearchFailedRow row = new SearchFailedRow();
            row.setId(rs.getLong("id"));
            row.setIndexType(rs.getString("index_type"));
            row.setBizId(rs.getLong("biz_id"));
            row.setEventType(rs.getString("event_type"));
            row.setErrorMsg(rs.getString("error_msg"));
            row.setRetryCount(rs.getInt("retry_count"));
            row.setStatus(rs.getString("status"));
            row.setNextRetryTime(rs.getTimestamp("next_retry_time") == null
                    ? null : rs.getTimestamp("next_retry_time").toLocalDateTime());
            return row;
        }, limit);
    }

    public void markFailedSuccess(long id) {
        jdbcTemplate.update("""
                UPDATE search_index_failed SET status = 'SUCCESS', updated_time = NOW() WHERE id = ?
                """, id);
    }

    /** 退避重试：30s * (retryCount+1)，全部由 DB 时钟计算 */
    public void markFailedRetry(long id, int retryCount) {
        jdbcTemplate.update("""
                UPDATE search_index_failed
                SET retry_count = retry_count + 1,
                    next_retry_time = DATE_ADD(NOW(), INTERVAL ? SECOND),
                    updated_time = NOW()
                WHERE id = ?
                """, 30 * (retryCount + 1), id);
    }

    public void markFailedTerminal(long id, String errorMsg) {
        jdbcTemplate.update("""
                UPDATE search_index_failed
                SET status = 'FAILED', error_msg = ?, updated_time = NOW()
                WHERE id = ?
                """, truncate(errorMsg), id);
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}
