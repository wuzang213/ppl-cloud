package com.hmdp.search.config;

import com.hmdp.search.es.EsClient;
import com.hmdp.search.es.EsIndexNames;
import com.hmdp.search.service.ISearchIndexRebuildService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动自检 + 首次全量初始化（P0 验收项在这里打印）。
 * ⚠️ 刻意不 fail fast：ES 没起来服务仍应能启动（搜索是读链路），否则会让"只有搜索挂了"演变成"search-service 起不来"；ES 不可用期间索引同步消息落失败表等重试补齐。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexInitializer implements ApplicationRunner {

    private final SearchProperties properties;

    private final EsClient esClient;

    private final ISearchIndexRebuildService rebuildService;

    @Override
    public void run(ApplicationArguments args) {
        String version;
        try {
            version = esClient.esVersion();
        } catch (Exception e) {
            log.error("【启动自检】连不上 Elasticsearch（{}:{}），跳过索引初始化；"
                            + "可稍后手动调 POST /search/admin/rebuild 重建",
                    properties.getHost(), properties.getPort(), e);
            return;
        }
        log.info("【启动自检】Elasticsearch 版本 = {}（客户端锁定 7.12.1，两者必须一致）", version);
        if (!version.startsWith("7.")) {
            log.error("【启动自检】ES 主版本不是 7.x，RestHighLevelClient 7.12.1 与之不兼容，"
                    + "索引同步会失败。当前版本={}", version);
        }
        if (!properties.isUseIk()) {
            log.warn("【启动自检】IK 分词已按配置关闭，中文检索将退化为单字切分");
        }

        if (!properties.isAutoRebuildOnStartup()) {
            log.warn("【启动自检】hmdp.search.auto-rebuild-on-startup=false，跳过启动初始化");
            return;
        }
        rebuildService.rebuildIfNeeded(EsIndexNames.TYPE_BLOG);
        rebuildService.rebuildIfNeeded(EsIndexNames.TYPE_SHOP);
    }
}
