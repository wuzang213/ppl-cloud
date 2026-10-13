package com.hmdp.search.es;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest.AliasActions;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.GetAliasesResponse;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.client.core.CountResponse;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.UpdateByQueryRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.script.Script;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ES 访问的唯一入口（RestHighLevelClient 7.12.1，与服务端版本严格对齐）。
 * 只做薄封装：把 index/delete/bulk/updateByQuery/alias/refresh/count/search 收口，具体查询 DSL 由调用方以 JSON 构造（见 {@code SearchQueryServiceImpl}），便于直接 curl 复现。 */
@Slf4j
@Component
public class EsClient {

    private static final RequestOptions OPTIONS = RequestOptions.DEFAULT;

    private final RestHighLevelClient client;

    private final ObjectMapper mapper;

    private final IndexDefinitions definitions;

    public EsClient(RestHighLevelClient client,
                    @Qualifier("esObjectMapper") ObjectMapper mapper,
                    IndexDefinitions definitions) {
        this.client = client;
        this.mapper = mapper;
        this.definitions = definitions;
    }

    // ==================== 索引/别名管理 ====================

    public boolean indexExists(String index) throws IOException {
        return client.indices().exists(new GetIndexRequest(index), OPTIONS);
    }

    public boolean aliasExists(String alias) throws IOException {
        return client.indices().existsAlias(new GetAliasesRequest(alias), OPTIONS);
    }

    /** 别名当前挂载的物理索引集合。
     * ⚠️ 别名不存在时必须返回空集合、不能抛 404：首次全量初始化（集群里还没有任何索引/别名）一定会走到这条路径，抛出去等于"第一次部署永远建不出索引"。 */
    public Set<String> indicesOfAlias(String alias) throws IOException {
        try {
            GetAliasesResponse response = client.indices().getAlias(new GetAliasesRequest(alias), OPTIONS);
            return new HashSet<>(response.getAliases().keySet());
        } catch (ElasticsearchStatusException e) {
            if (e.status() == RestStatus.NOT_FOUND) {
                log.debug("别名 {} 不存在，按「未挂载任何索引」处理（首次初始化时正常）", alias);
                return Set.of();
            }
            throw e;
        }
    }

    public void createIndex(String index, String type, String refreshInterval) throws IOException {
        Map<String, Object> body = definitions.body(type, refreshInterval);
        CreateIndexRequest request = new CreateIndexRequest(index).source(body);
        client.indices().create(request, OPTIONS);
        log.info("已创建 ES 索引 {}（refresh_interval={}）", index, refreshInterval);
    }

    public void deleteIndex(String index) throws IOException {
        AcknowledgedResponse response = client.indices().delete(new DeleteIndexRequest(index), OPTIONS);
        log.info("已删除 ES 索引 {}，acknowledged={}", index, response.isAcknowledged());
    }

    /** 原子切换别名：一次 {@code _aliases} 请求内先摘旧索引、再挂新索引，中间无空窗期。
     * ⚠️ 旧索引集合由本方法自己查（不接受调用方传入，快照可能过期、且多一次 ES 往返）；@return 被摘下来的旧索引集合（首次建别名时为空集）。 */
    public Set<String> switchAlias(String alias, String newIndex) throws IOException {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        Set<String> olds = indicesOfAlias(alias);
        for (String current : olds) {
            if (!current.equals(newIndex)) {
                request.addAliasAction(AliasActions.remove().index(current).alias(alias));
            }
        }
        request.addAliasAction(AliasActions.add().index(newIndex).alias(alias));
        client.indices().updateAliases(request, OPTIONS);
        log.info("别名 {} 已原子切换到 {}（旧索引 {} 保留待清理）", alias, newIndex, olds);
        return olds;
    }

    public void refresh(String index) throws IOException {
        client.indices().refresh(new RefreshRequest(index), OPTIONS);
    }

    /** 改 refresh_interval：重建期间设 -1 关刷新（吞吐翻倍），收尾设回 1s 再手动 refresh，保证 count 校验拿到真实文档数。 */
    public void updateRefreshInterval(String index, String interval) throws IOException {
        Settings settings = Settings.builder().put("index.refresh_interval", interval).build();
        UpdateSettingsRequest request = new UpdateSettingsRequest(index).settings(settings);
        client.indices().putSettings(request, OPTIONS);
        log.info("已调整 {} 的 refresh_interval = {}", index, interval);
    }

    /** 列出形如 {@code hmdp_blog_v*} 的物理索引名，用于推算下一个版本号。
     * ⚠️ 无任何索引匹配时必须返回空列表：`_cat/indices/<pattern>` 匹配不到时会返回 404 index_not_found_exception，不兜住它 {@code nextVersion()} 就抛异常、第一次部署永远建不出索引。 */
    public List<String> listPhysicalIndices(String prefix) throws IOException {
        Request request = new Request("GET", "/_cat/indices/" + prefix + "*?h=index&format=json");
        Response response;
        try {
            response = client.getLowLevelClient().performRequest(request);
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                log.debug("没有匹配 {} 的物理索引，按空列表处理（首次初始化时正常）", prefix);
                return List.of();
            }
            throw e;
        }
        String body = EntityUtils.toString(response.getEntity());
        List<Map<String, Object>> rows = mapper.readValue(body, new TypeReference<List<Map<String, Object>>>() {
        });
        List<String> indices = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object name = row.get("index");
            if (name != null) {
                indices.add(String.valueOf(name));
            }
        }
        return indices;
    }

    // ==================== 文档写入 ====================

    /** 单文档覆盖写：{@code _id} = 业务主键，天然幂等 */
    public void indexDoc(String index, String id, Object doc) throws IOException {
        IndexRequest request = new IndexRequest(index).id(id).source(toJson(doc), XContentType.JSON);
        client.index(request, OPTIONS);
    }

    /** 删除文档；文档本来就不存在（404）时静默返回，不算失败 */
    public void deleteDoc(String index, String id) throws IOException {
        try {
            client.delete(new DeleteRequest(index, id), OPTIONS);
        } catch (ElasticsearchStatusException e) {
            if (e.status() == RestStatus.NOT_FOUND) {
                log.debug("待删除文档不存在，忽略 index={}, id={}", index, id);
                return;
            }
            throw e;
        }
    }

    /** 批量写入（重建时用）。返回失败条数 —— 只要有一条失败就算本轮不完整，调用方据此让任务失败并重试，避免"重建完成但缺了几百条"的静默残缺。 */
    public int bulkIndex(String index, Map<String, Object> idToDoc) throws IOException {
        if (idToDoc.isEmpty()) {
            return 0;
        }
        BulkRequest request = new BulkRequest();
        for (Map.Entry<String, Object> entry : idToDoc.entrySet()) {
            request.add(new IndexRequest(index).id(entry.getKey())
                    .source(toJson(entry.getValue()), XContentType.JSON));
        }
        BulkResponse response = client.bulk(request, OPTIONS);
        int failed = 0;
        for (BulkItemResponse item : response.getItems()) {
            if (item.isFailed()) {
                failed++;
                if (failed <= 3) {
                    log.error("bulk 写入失败 index={}, id={}, reason={}",
                            index, item.getId(), item.getFailureMessage());
                }
            }
        }
        if (failed > 0) {
            log.error("bulk 写入部分失败 index={}, 总数={}, 失败={}", index, idToDoc.size(), failed);
        }
        return failed;
    }

    /** 批量更新（引用方冗余字段刷新用，如作者改名）：同步执行 + {@code conflicts=proceed}，数据量小（本项目文章 72 行）时容忍并发写版本冲突、避免整批 abort。
     * 真要处理大索引应换成 {@code submitUpdateByQueryTask} 异步执行。 */
    public long updateByQuery(String index, QueryBuilder query, Script script) throws IOException {
        UpdateByQueryRequest request = new UpdateByQueryRequest(index);
        request.setQuery(query);
        request.setScript(script);
        request.setConflicts("proceed");
        request.setRefresh(true);
        BulkByScrollResponse response = client.updateByQuery(request, OPTIONS);
        long updated = response.getUpdated();
        if (!response.getBulkFailures().isEmpty()) {
            log.error("_update_by_query 存在失败项 index={}, 失败数={}", index, response.getBulkFailures().size());
        }
        return updated;
    }

    // ==================== 查询与统计 ====================

    public SearchResponse search(SearchRequest request) throws IOException {
        return client.search(request, OPTIONS);
    }

    public long count(String index) throws IOException {
        CountResponse response = client.count(new CountRequest(index), OPTIONS);
        return response.getCount();
    }

    /** 扫描索引里的全部 id（对账用）。用 {@code search_after} 而非 {@code scroll}（后者为离线导出设计，会长时间占用堆与文件句柄）。 */
    public List<Long> scanAllIds(String index, int maxDocs) throws IOException {
        List<Long> ids = new ArrayList<>();
        Object[] searchAfter = null;
        while (ids.size() < maxDocs) {
            SearchSourceBuilder source = new SearchSourceBuilder()
                    .fetchSource(new String[]{"id"}, null)
                    .size(1000)
                    .sort("id", SortOrder.ASC)
                    .query(org.elasticsearch.index.query.QueryBuilders.existsQuery("id"));
            if (searchAfter != null) {
                source.searchAfter(searchAfter);
            }
            SearchRequest request = new SearchRequest(index).source(source);
            SearchHit[] hits = client.search(request, OPTIONS).getHits().getHits();
            if (hits.length == 0) {
                break;
            }
            for (SearchHit hit : hits) {
                Object id = hit.getSourceAsMap().get("id");
                if (id instanceof Number) {
                    ids.add(((Number) id).longValue());
                }
            }
            searchAfter = hits[hits.length - 1].getSortValues();
        }
        return ids;
    }

    private String toJson(Object doc) throws IOException {
        return mapper.writeValueAsString(doc);
    }

    /** 读服务端版本号。⚠️ 这是 P0 验收项：必须确认是 7.12.1，服务端与客户端大版本不一致（尤其服务端 8.x）时 RestHighLevelClient 会报错或静默行为异常。 */
    public String esVersion() throws IOException {
        Response response = client.getLowLevelClient().performRequest(new Request("GET", "/"));
        String body = EntityUtils.toString(response.getEntity());
        return mapper.readTree(body).path("version").path("number").asText();
    }
}
