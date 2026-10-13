package com.hmdp.search.service;

import com.hmdp.search.domain.RebuildOutcome;
import com.hmdp.search.domain.SearchRebuildTask;

import java.util.List;
import java.util.Map;

/** 全量初始化与重建：影子索引 + 别名原子切换 + 重建期间双写追平。用 Redis 租约锁保证互斥，重建期间变更靠事件重放追平（不依赖 update_time 区间扫描）。 */
public interface ISearchIndexRebuildService {

    /** 启动时自检：别名缺失或文档数为 0 则自动全量重建；ES 不可用时静默跳过 */
    void rebuildIfNeeded(String type);

    /** 全量重建（幂等入口），返回 SKIPPED / SUCCESS / FAILED 三态 */
    RebuildOutcome rebuild(String type);

    /** 集群里所有与搜索相关的物理索引（运维确认/清理用） */
    Map<String, List<String>> listIndices();

    /** 查询最近一次重建任务（供运维接口展示进度） */
    SearchRebuildTask getLatestRebuildTask(String type);
}
