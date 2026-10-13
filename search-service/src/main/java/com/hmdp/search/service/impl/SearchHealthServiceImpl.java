package com.hmdp.search.service.impl;

import com.hmdp.search.es.EsClient;
import com.hmdp.search.es.EsIndexNames;
import com.hmdp.search.service.ISearchHealthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查实现：直接读 ES 的版本与别名状态，组装成非敏感的 status map。
 */
@Slf4j
@Service
public class SearchHealthServiceImpl implements ISearchHealthService {

    private final EsClient esClient;

    public SearchHealthServiceImpl(EsClient esClient) {
        this.esClient = esClient;
    }

    @Override
    public Map<String, Object> health() {
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("esVersion", esClient.esVersion());
            boolean blogAliasExists = esClient.aliasExists(EsIndexNames.BLOG_ALIAS);
            boolean shopAliasExists = esClient.aliasExists(EsIndexNames.SHOP_ALIAS);
            info.put("blogAliasExists", blogAliasExists);
            info.put("shopAliasExists", shopAliasExists);
            if (blogAliasExists) {
                info.put("blogCount", esClient.count(EsIndexNames.BLOG_ALIAS));
            }
            if (shopAliasExists) {
                info.put("shopCount", esClient.count(EsIndexNames.SHOP_ALIAS));
            }
            return info;
        } catch (IOException e) {
            throw new IllegalStateException("Elasticsearch 不可用", e);
        }
    }
}
