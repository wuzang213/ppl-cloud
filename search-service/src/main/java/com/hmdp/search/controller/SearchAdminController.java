package com.hmdp.search.controller;

import com.hmdp.common.domain.Result;
import com.hmdp.search.domain.RebuildOutcome;
import com.hmdp.search.domain.SearchRebuildTask;
import com.hmdp.search.domain.vo.ConsistencyReport;
import com.hmdp.search.service.ISearchIndexRebuildService;
import com.hmdp.search.service.ISearchVerifyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 搜索运维接口（内网直连，勿走网关）。
 * ⚠️ /search/admin/** 在网关被 403（internalPaths 黑名单优先级高于 excludePaths 白名单），否则任何人都能触发全量重建、把 ES 打满。 */
@Tag(name = "搜索运维", description = "全量重建 / 重建进度 / 一致性校验与修复（内网直连，勿走网关）")
@RestController
@RequestMapping("/search/admin")
@RequiredArgsConstructor
public class SearchAdminController {

    private final ISearchIndexRebuildService rebuildService;

    private final ISearchVerifyService verifyService;

    @Operation(summary = "触发全量重建（影子索引 + 别名原子切换，期间搜索不中断）")
    @PostMapping("/rebuild")
    public Result rebuild(@RequestParam("type") @Parameter(description = "blog|shop") String type) {
        RebuildOutcome outcome = rebuildService.rebuild(type);
        Map<String, Object> data = new LinkedHashMap<>(3);
        // ⚠️ status 必看：SKIPPED（没抢到锁、没跑）/ SUCCESS / FAILED 是三件不同的事。
        // 早先只回一个 boolean triggered，"没跑"和"跑了但失败"都变成 false，
        // 运维会把一次真实失败当成"有人在跑"而不再跟进。
        data.put("status", outcome.name());
        data.put("triggered", outcome != RebuildOutcome.SKIPPED);
        data.put("message", switch (outcome) {
            case SUCCESS -> "重建已完成，别名已切换";
            case FAILED -> "重建已执行但失败：半成品索引已清理，别名保持原状；详情看 /search/admin/rebuild/status 与服务日志";
            case SKIPPED -> "已有同类型的重建任务在执行，本次跳过";
        });
        return Result.ok(data);
    }

    @Operation(summary = "查询最近一次重建的进度")
    @GetMapping("/rebuild/status")
    public Result rebuildStatus(@RequestParam("type") @Parameter(description = "blog|shop") String type) {
        SearchRebuildTask task = rebuildService.getLatestRebuildTask(type);
        if (task == null) {
            return Result.ok(null);
        }
        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("status", task.getStatus());
        data.put("targetIndex", task.getTargetIndex());
        data.put("processed", task.getProcessed());
        data.put("total", task.getTotal());
        data.put("startedAt", task.getStartedAt());
        data.put("finishedAt", task.getFinishedAt());
        data.put("errorMsg", task.getErrorMsg());
        long total = task.getTotal() == null ? 0 : task.getTotal();
        data.put("percent", total == 0 ? 0 : Math.round(task.getProcessed() * 10000.0 / total) / 100.0);
        return Result.ok(data);
    }

    @Operation(summary = "一致性校验（只出报告，不改数据）")
    @PostMapping("/verify")
    public Result verify(@RequestParam("type") @Parameter(description = "blog|shop") String type) {
        ConsistencyReport report = verifyService.verify(type);
        return Result.ok(report);
    }

    @Operation(summary = "一致性校验并自动修复（删残留 + 补缺失）")
    @PostMapping("/repair")
    public Result repair(@RequestParam("type") @Parameter(description = "blog|shop") String type) {
        ConsistencyReport report = verifyService.repair(type);
        return Result.ok(report);
    }

    @Operation(summary = "查看集群里搜索相关的物理索引（用于确认版本与清理旧索引）")
    @GetMapping("/indices")
    public Result indices() {
        return Result.ok(rebuildService.listIndices());
    }
}
