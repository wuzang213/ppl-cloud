package com.hmdp.search.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.search.service.ISearchQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.search.config.SearchProperties;
import com.hmdp.search.service.PagingGuard;
import com.hmdp.search.domain.vo.SearchHitVO;
import com.hmdp.search.domain.vo.SearchPageVO;
import com.hmdp.search.domain.vo.ShopHitVO;
import com.hmdp.search.es.EsClient;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.hmdp.search.es.EsIndexNames.BLOG_ALIAS;
import static com.hmdp.search.es.EsIndexNames.SHOP_ALIAS;
import static com.hmdp.search.util.HighlightSanitizer.HL_POST;
import static com.hmdp.search.util.HighlightSanitizer.HL_PRE;
import static com.hmdp.search.util.HighlightSanitizer.safeHighlight;

/** 搜索查询：构造 DSL（用 Map→JSON 而非 typed builder，便于直接复制到 curl 复现）、执行、解析结果。免登录接口的三道护栏（size 钳制 / 深分页拒绝 / 空 key 返回空）在本类。 */
@Slf4j
@Service
public class SearchQueryServiceImpl implements ISearchQueryService {

    // 高亮占位符与 safeHighlight 已搬到 HighlightSanitizer（XSS 边界，必须能被离线单测断言），此处静态导入。

    /** 解析 search body 的 {@code NamedXContentRegistry}。⚠️ 绝不能用 {@code NamedXContentRegistry.EMPTY}：body 里的 query/sort/aggs/function_score 全是 named object，EMPTY 会让任何查询都抛 "named objects are not supported"，且报错像"格式不支持"极易误判。
     * 正确做法是用 {@link SearchModule#getNamedXContents()} 注册 ES 核心检索组件（不加载插件）。 */
    private static final NamedXContentRegistry SEARCH_XCONTENT_REGISTRY = new NamedXContentRegistry(
            new SearchModule(Settings.EMPTY, false, Collections.emptyList()).getNamedXContents());

    private final EsClient esClient;

    private final SearchProperties properties;

    private final ObjectMapper esObjectMapper;

    public SearchQueryServiceImpl(EsClient esClient,
                              SearchProperties properties,
                              @Qualifier("esObjectMapper") ObjectMapper esObjectMapper) {
        this.esClient = esClient;
        this.properties = properties;
        this.esObjectMapper = esObjectMapper;
    }

    // ==================== 文章搜索 ====================

    public SearchPageVO<SearchHitVO> searchBlog(String key, Integer page, Integer size,
                                                String sort, Long shopId, Long authorId) {
        SearchPageVO<SearchHitVO> empty = emptyPage();
        if (StrUtil.isBlank(key)) {
            // 绝不允许退化成 match_all —— 否则一个空参数就能把 ES 打满
            return empty;
        }
        int[] paging = paging(page, size);
        int from = paging[0];
        int sizeClamped = paging[1];

        List<Object> filters = new ArrayList<>(2);
        if (shopId != null) {
            filters.add(map("term", map("shopId", shopId)));
        }
        if (authorId != null) {
            filters.add(map("term", map("authorId", authorId)));
        }

        Map<String, Object> bool = map("must", List.of(map("multi_match", map(
                "query", key,
                "fields", List.of("title^3", "summary^2", "content^1", "authorName^1.5"),
                "type", "best_fields",
                "operator", "or",
                "minimum_should_match", "30%"))));
        if (!filters.isEmpty()) {
            // filter 里的条件不参与打分，可命中 filter cache，比放 must 更快
            bool.put("filter", filters);
        }

        Map<String, Object> functionScore = map(
                "query", map("bool", bool),
                "functions", List.of(
                        // log1p 必须加：否则一条万赞笔记会用热度碾压所有相关性
                        map("field_value_factor", map("field", "liked", "modifier", "log1p",
                                "factor", 0.3, "missing", 0)),
                        map("field_value_factor", map("field", "viewCount", "modifier", "log1p",
                                "factor", 0.1, "missing", 0))),
                "score_mode", "sum",
                "boost_mode", "sum");

        Map<String, Object> body = map("query", map("function_score", functionScore));
        body.put("highlight", map(
                "pre_tags", List.of(HL_PRE),
                "post_tags", List.of(HL_POST),
                "fields", map(
                        "title", map("number_of_fragments", 0),
                        "content", map("fragment_size", 120, "number_of_fragments", 1))));
        body.put("sort", blogSort(sort));
        body.put("from", from);
        body.put("size", sizeClamped);

        SearchResponse response = execute(BLOG_ALIAS, body);
        SearchPageVO<SearchHitVO> pageResult = new SearchPageVO<>();
        pageResult.setTotal(totalOf(response));
        List<SearchHitVO> hits = new ArrayList<>();
        for (SearchHit hit : response.getHits().getHits()) {
            SearchHitVO vo = esObjectMapper.convertValue(hit.getSourceAsMap(), SearchHitVO.class);
            vo.setScore(hit.getScore());
            vo.setTitleHighlight(safeHighlight(highlightOf(hit, "title"), vo.getTitle()));
            String snippet = highlightOf(hit, "content");
            if (snippet == null) {
                Object summary = hit.getSourceAsMap().get("summary");
                snippet = summary == null ? null : String.valueOf(summary);
            }
            vo.setSnippet(safeHighlight(snippet, null));
            hits.add(vo);
        }
        pageResult.setHits(hits);
        pageResult.setHasMore(from + hits.size() < pageResult.getTotal());
        return pageResult;
    }

    private List<Object> blogSort(String sort) {
        if ("time".equalsIgnoreCase(sort)) {
            return List.of(map("createTime", map("order", "desc")), map("id", map("order", "desc")));
        }
        if ("hot".equalsIgnoreCase(sort)) {
            return List.of(map("liked", map("order", "desc")), map("viewCount", map("order", "desc")),
                    map("id", map("order", "desc")));
        }
        // 默认相关度优先；同分再按 updateTime，最后用 id 兜底 —— 排序键必须"全序"，
        // 否则同分文档在分片内的相对顺序不稳定，翻页会出现重复/漏项。
        // ⚠️ 店铺侧（shopSort）每个分支都带 id 兜底，这里必须保持一致。
        return List.of(map("_score", map("order", "desc")),
                map("updateTime", map("order", "desc")),
                map("id", map("order", "desc")));
    }

    // ==================== 店铺搜索（兼分类搜索）====================

    /** 店铺搜索。{@code typeName} 参与检索是这里的核心设计：店名往往不含品类词（如「蜀大侠」），把分类名冗余进店铺文档后搜「火锅」才能出所有火锅店；{@code typeId} 作筛选条件（{@code filter}，不参与打分）。
     * 传 {@code x/y} 时用 {@code gauss} 衰减做「关键词 + 距离」混合排序（而非直接按距离排序丢掉相关性），{@code radius} 作 geo 过滤条件；⚠️ 项目里 x = 经度、y = 纬度。 */
    public SearchPageVO<ShopHitVO> searchShop(String key, Integer page, Integer size, String sort,
                                              Long typeId, Double x, Double y, Integer radius) {
        if (StrUtil.isBlank(key)) {
            return emptyPage();
        }
        int[] paging = paging(page, size);
        int from = paging[0];
        int sizeClamped = paging[1];

        List<Object> filters = new ArrayList<>(2);
        if (typeId != null) {
            filters.add(map("term", map("typeId", typeId)));
        }
        if (x != null && y != null && radius != null && radius > 0) {
            filters.add(map("geo_distance", map(
                    "distance", radius + "m",
                    "location", map("lat", y, "lon", x))));
        }

        Map<String, Object> bool = map("must", List.of(map("multi_match", map(
                "query", key,
                "fields", List.of("name^3", "typeName^2", "area^1", "address^1"),
                "type", "best_fields",
                "operator", "or",
                "minimum_should_match", "30%"))));
        if (!filters.isEmpty()) {
            bool.put("filter", filters);
        }

        List<Object> functions = new ArrayList<>();
        functions.add(map("field_value_factor", map("field", "score", "modifier", "none",
                "factor", 0.5, "missing", 0)));
        functions.add(map("field_value_factor", map("field", "sold", "modifier", "log1p",
                "factor", 0.2, "missing", 0)));
        if (x != null && y != null) {
            // 高斯衰减：1km 内几乎不衰减，3km 处衰减一半 —— 近的加权，但不淹没关键词相关性
            functions.add(map("gauss", map("location", map(
                    "origin", map("lat", y, "lon", x),
                    "offset", "1km", "scale", "3km", "decay", 0.5))));
        }

        Map<String, Object> body = map(
                "query", map("function_score", map(
                        "query", map("bool", bool),
                        "functions", functions,
                        "score_mode", "sum",
                        "boost_mode", "sum")),
                "highlight", map(
                        "pre_tags", List.of(HL_PRE),
                        "post_tags", List.of(HL_POST),
                        "fields", map(
                                "name", map("number_of_fragments", 0),
                                "address", map("fragment_size", 100, "number_of_fragments", 1))),
                // 分类聚合 = 左侧筛选器；分类不独立建索引也能直接出筛选项
                "aggs", map("typeName", map("terms", map("field", "typeName.keyword", "size", 20))),
                "sort", shopSort(sort));
        body.put("from", from);
        body.put("size", sizeClamped);

        SearchResponse response = execute(SHOP_ALIAS, body);
        SearchPageVO<ShopHitVO> pageResult = new SearchPageVO<>();
        pageResult.setTotal(totalOf(response));
        List<ShopHitVO> hits = new ArrayList<>();
        for (SearchHit hit : response.getHits().getHits()) {
            ShopHitVO vo = esObjectMapper.convertValue(hit.getSourceAsMap(), ShopHitVO.class);
            vo.setDocScore(hit.getScore());
            vo.setNameHighlight(safeHighlight(highlightOf(hit, "name"), vo.getName()));
            hits.add(vo);
        }
        pageResult.setHits(hits);
        pageResult.setHasMore(from + hits.size() < pageResult.getTotal());
        pageResult.setAggs(parseTermsAggs(response));
        return pageResult;
    }

    private List<Object> shopSort(String sort) {
        if ("price".equalsIgnoreCase(sort)) {
            return List.of(map("avgPrice", map("order", "asc")), map("id", map("order", "asc")));
        }
        if ("sold".equalsIgnoreCase(sort)) {
            return List.of(map("sold", map("order", "desc")), map("id", map("order", "asc")));
        }
        if ("score".equalsIgnoreCase(sort)) {
            return List.of(map("score", map("order", "desc")), map("id", map("order", "asc")));
        }
        return List.of(map("_score", map("order", "desc")), map("id", map("order", "asc")));
    }

    // ==================== 搜索联想 ====================

    public List<String> suggest(String prefix, String type) {
        if (StrUtil.isBlank(prefix)) {
            return Collections.emptyList();
        }
        boolean blog = !"shop".equalsIgnoreCase(type);
        String index = blog ? BLOG_ALIAS : SHOP_ALIAS;
        String field = blog ? "title" : "name";
        Map<String, Object> body = map(
                "query", map("match_phrase_prefix", map(field, map("query", prefix))),
                "_source", List.of(blog ? "title" : "name"),
                "size", 10);
        SearchResponse response = execute(index, body);
        List<String> suggestions = new ArrayList<>(10);
        for (SearchHit hit : response.getHits().getHits()) {
            Object value = hit.getSourceAsMap().get(field);
            if (value != null) {
                suggestions.add(String.valueOf(value));
            }
        }
        return suggestions;
    }

    // ==================== 内部工具 ====================

    /** 分页护栏：{@code size} 钳到上限、深分页直接 400。判定逻辑在 {@link PagingGuard#clamp}（可离线测试的纯函数），本方法退化为"把配置项喂进去"的委托，异常类型/消息/边界与搬迁前完全一致。 */
    private int[] paging(Integer page, Integer size) {
        return PagingGuard.clamp(page, size, properties.getMaxPageSize(), properties.getMaxWindow());
    }

    private SearchResponse execute(String index, Map<String, Object> body) {
        try {
            String json = esObjectMapper.writeValueAsString(body);
            log.debug("ES 查询 index={}, body={}", index, json);
            return esClient.search(new SearchRequest(index).source(parseSource(json)));
        } catch (IOException e) {
            throw new IllegalStateException("搜索失败，index=" + index, e);
        }
    }

    private SearchSourceBuilder parseSource(String json) throws IOException {
        // 必须传能解析 named object 的注册表，否则 query/sort/aggs 一律解析失败（见 SEARCH_XCONTENT_REGISTRY）
        try (XContentParser parser = XContentFactory.xContent(XContentType.JSON)
                .createParser(SEARCH_XCONTENT_REGISTRY, LoggingDeprecationHandler.INSTANCE, json)) {
            return SearchSourceBuilder.fromXContent(parser);
        }
    }

    private long totalOf(SearchResponse response) {
        return response.getHits().getTotalHits() == null ? 0L : response.getHits().getTotalHits().value;
    }

    private String highlightOf(SearchHit hit, String field) {
        HighlightField highlight = hit.getHighlightFields().get(field);
        if (highlight == null || highlight.fragments().length == 0) {
            return null;
        }
        return highlight.fragments()[0].string();
    }

    private Map<String, Map<String, Long>> parseTermsAggs(SearchResponse response) {
        if (response.getAggregations() == null) {
            return Collections.emptyMap();
        }
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        for (Aggregation aggregation : response.getAggregations()) {
            if (aggregation instanceof Terms terms) {
                Map<String, Long> buckets = new LinkedHashMap<>();
                for (Terms.Bucket bucket : terms.getBuckets()) {
                    buckets.put(bucket.getKeyAsString(), bucket.getDocCount());
                }
                result.put(aggregation.getName(), buckets);
            }
        }
        return result;
    }

    private <T> SearchPageVO<T> emptyPage() {
        SearchPageVO<T> empty = new SearchPageVO<>();
        empty.setTotal(0);
        empty.setHits(Collections.emptyList());
        empty.setHasMore(false);
        empty.setAggs(Collections.emptyMap());
        return empty;
    }

    /** 顺序无关的 map 构造（{@code null} 值允许，便于可选字段）。
     * ⚠️ 不能为"简洁"改成字符串模板拼 JSON：用户的 {@code key} 直接进 JSON，拼接会引入 JSON 注入与解析失败，必须走 Jackson 序列化。 */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            result.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return result;
    }
}
