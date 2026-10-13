package com.hmdp.search.task;

import com.hmdp.search.domain.vo.ConsistencyReport;
import com.hmdp.search.es.EsIndexNames;
import com.hmdp.search.service.ISearchVerifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 索引对账任务（每天凌晨 3 点）。兜的是这套方案唯一承认的缺口：绕过业务代码的删除（如 DBA 直接 {@code DELETE FROM tb_blog}）不产生 outbox 事件，ES 文档就永远删不掉，对账周期即残留窗口上限（默认 1 天）。
 * 只比对 id 集合（不做逐字段比对）：O(N) 集合差运算成本极低、可天天跑；字段级不一致（作者名陈旧之类）不影响"能不能搜到"，抽样人工排查即可。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexReconcileTask {

    private final ISearchVerifyService verifyService;

    @Scheduled(cron = "0 0 3 * * ?")
    public void reconcile() {
        for (String type : new String[]{EsIndexNames.TYPE_BLOG, EsIndexNames.TYPE_SHOP}) {
            try {
                ConsistencyReport report = verifyService.repair(type);
                if (report.isConsistent()) {
                    log.info("索引对账通过 type={}, business={}, es={}",
                            type, report.getBusinessCount(), report.getEsCount());
                } else {
                    log.warn("索引对账发现并修复差异 type={}, 删除残留={}, 补齐缺失={}",
                            type, report.getEsOnly().size(), report.getBusinessOnly().size());
                }
            } catch (Exception e) {
                log.error("索引对账失败 type={}", type, e);
            }
        }
    }
}
