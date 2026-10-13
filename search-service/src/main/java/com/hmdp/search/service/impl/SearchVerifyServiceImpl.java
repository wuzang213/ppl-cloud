package com.hmdp.search.service.impl;

import com.hmdp.search.service.ISearchVerifyService;
import com.hmdp.search.service.ISearchSourceReader;
import com.hmdp.search.service.ISearchIndexService;
import com.hmdp.search.domain.vo.ConsistencyReport;
import com.hmdp.search.es.EsClient;
import com.hmdp.search.es.EsIndexNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 索引一致性校验与修复：只比对 id 集合（成本极低、可每天跑），repair 幂等（删不存在的文档被忽略、写同 id 覆盖）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchVerifyServiceImpl implements ISearchVerifyService {

    /** 扫描上限，防止误连生产大索引时一次拉爆内存 */
    private static final int MAX_SCAN = 200_000;

    private final EsClient esClient;

    private final ISearchSourceReader reader;

    private final ISearchIndexService indexService;

    public ConsistencyReport verify(String type) {
        String alias = EsIndexNames.aliasOf(type);
        try {
            Set<Long> businessIds = reader.collectAllIds(type, null);
            Set<Long> esIds = new HashSet<>(esClient.scanAllIds(alias, MAX_SCAN));

            List<Long> esOnly = new ArrayList<>();
            for (Long id : esIds) {
                if (!businessIds.contains(id)) {
                    esOnly.add(id);
                }
            }
            List<Long> businessOnly = new ArrayList<>();
            for (Long id : businessIds) {
                if (!esIds.contains(id)) {
                    businessOnly.add(id);
                }
            }

            ConsistencyReport report = new ConsistencyReport();
            report.setType(type);
            report.setBusinessCount(businessIds.size());
            report.setEsCount(esIds.size());
            report.setEsOnly(esOnly);
            report.setBusinessOnly(businessOnly);
            report.setConsistent(esOnly.isEmpty() && businessOnly.isEmpty());
            if (!report.isConsistent()) {
                log.warn("索引一致性校验发现差异 type={}, business={}, es={}, esOnly={}, businessOnly={}",
                        type, businessIds.size(), esIds.size(), esOnly.size(), businessOnly.size());
            }
            return report;
        } catch (IOException e) {
            throw new IllegalStateException("一致性校验失败，type=" + type, e);
        }
    }

    /** 校验并自动修复：ES 多出来的删掉、业务多出来的补索引。修复动作都是幂等的（删不存在的文档被忽略、写同 id 覆盖），故修复过程中对账任务重跑也不会出问题。 */
    public ConsistencyReport repair(String type) {
        ConsistencyReport report = verify(type);
        String alias = EsIndexNames.aliasOf(type);
        try {
            for (Long id : report.getEsOnly()) {
                esClient.deleteDoc(alias, String.valueOf(id));
            }
            for (Long id : report.getBusinessOnly()) {
                indexService.apply(type, id);
            }
        } catch (IOException e) {
            throw new IllegalStateException("一致性修复失败，type=" + type, e);
        }
        report.setRepaired(true);
        if (!report.isConsistent()) {
            log.warn("索引一致性修复完成 type={}, 删除残留={}, 补齐缺失={}",
                    type, report.getEsOnly().size(), report.getBusinessOnly().size());
        }
        return report;
    }
}
