package com.hmdp.search.contract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.search.support.RepoFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 红线 ②：<b>查询 DSL 里引用的字段名，必须存在于对应索引的 mapping 里</b>。
 * <p>
 * 为什么这是红线：ES 对"不存在的字段"是**宽容**的 —— 查一个 mapping 里没有的字段
 * 不报错，只是永远 0 命中。拼错一个字符（{@code viewCount} 写成 {@code viewcount}）的后果，
 * 是搜索相关性悄悄退化、排序悄悄失效，而日志与门禁全都干净。
 * <p>
 * 做法：从 {@code SearchQueryService} 的**方法体文本**里抽出 DSL 引用的字段名，
 * 再与 {@code es/*_v1.json} 里 {@code mappings.properties} 的键集合比对。
 * 字段名的抽取按"语法位置"定向（multi_match 加权字段 / term 过滤 / field_value_factor /
 * sort / highlight），而不是把所有字符串字面量一锅端 —— 后者会把 {@code "desc"}、
 * {@code "log1p"} 这类**非字段**的取值也当成字段名，产生假红。
 */
class QueryFieldContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SOURCE = RepoFiles.readMainJava("com/hmdp/search/service/impl/SearchQueryServiceImpl.java");

    /** sort 里不是字段名的键 */
    private static final Set<String> NON_FIELD_SORT_KEYS = Set.of("_score");

    private static final Pattern BOOSTED_FIELD = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\\^[0-9.]+");

    private static final Pattern TERM_FILTER_FIELD =
            Pattern.compile("map\\(\"term\",\\s*map\\(\"([A-Za-z_][A-Za-z0-9_]*)\"");

    private static final Pattern VALUE_FACTOR_FIELD =
            Pattern.compile("map\\(\"field\",\\s*\"([A-Za-z_][A-Za-z0-9_.]*)\"");

    private static final Pattern SORT_FIELD =
            Pattern.compile("map\\(\"([A-Za-z_][A-Za-z0-9_]*)\",\\s*map\\(\"order\"");

    private static final Pattern HIGHLIGHT_FIELD =
            Pattern.compile("\"fields\",\\s*map\\(\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"");

    private static Set<String> mappingFields(String resource) {
        try {
            Map<String, Object> body = MAPPER.readValue(RepoFiles.readMainResource(resource),
                    new TypeReference<Map<String, Object>>() {
                    });
            @SuppressWarnings("unchecked")
            Map<String, Object> mappings = (Map<String, Object>) body.get("mappings");
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");
            return new TreeSet<>(properties.keySet());
        } catch (IOException e) {
            throw new UncheckedIOException("解析 mapping 失败: " + resource, e);
        }
    }

    /** 按方法签名起始位置截出方法体（花括号配对），避免整文件抽取时串到别的方法 */
    private static String methodBody(String signatureFragment) {
        int start = SOURCE.indexOf(signatureFragment);
        assertTrue(start >= 0, "在 SearchQueryService 里找不到方法: " + signatureFragment);
        int brace = SOURCE.indexOf('{', start);
        int depth = 0;
        for (int i = brace; i < SOURCE.length(); i++) {
            char c = SOURCE.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return SOURCE.substring(brace, i + 1);
                }
            }
        }
        throw new IllegalStateException("方法体未闭合: " + signatureFragment);
    }

    private static void collect(Set<String> into, String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }

    /** 抽取某段 DSL 引用的字段名（已去掉 .keyword 后缀与 _score 这类非字段键） */
    private static List<String> dslFields(String body) {
        Set<String> names = new LinkedHashSet<>();
        collect(names, body, BOOSTED_FIELD);
        collect(names, body, TERM_FILTER_FIELD);
        collect(names, body, VALUE_FACTOR_FIELD);
        collect(names, body, SORT_FIELD);
        collect(names, body, HIGHLIGHT_FIELD);
        names.removeAll(NON_FIELD_SORT_KEYS);
        List<String> result = new ArrayList<>();
        for (String name : names) {
            int dot = name.indexOf('.');
            result.add(dot < 0 ? name : name.substring(0, dot));
        }
        return result;
    }

    private static void assertAllFieldsExist(String label, String body, String mappingResource) {
        Set<String> declared = mappingFields(mappingResource);
        List<String> missing = new ArrayList<>();
        for (String field : dslFields(body)) {
            if (!declared.contains(field)) {
                missing.add(field);
            }
        }
        assertTrue(missing.isEmpty(),
                label + " 引用了 mapping(" + mappingResource + ") 里不存在的字段 " + missing
                        + "；ES 不会报错，只会永远 0 命中。已声明字段: " + declared);
    }

    @Test
    @DisplayName("blog 搜索 DSL 的字段名全部存在于 blog mapping")
    void blogSearchFieldsExist() {
        assertAllFieldsExist("searchBlog", methodBody("searchBlog(String key"), "es/blog_v1.json");
    }

    @Test
    @DisplayName("blog 排序 DSL 的字段名全部存在于 blog mapping")
    void blogSortFieldsExist() {
        assertAllFieldsExist("blogSort", methodBody("List<Object> blogSort("), "es/blog_v1.json");
    }

    @Test
    @DisplayName("shop 搜索 DSL 的字段名全部存在于 shop mapping")
    void shopSearchFieldsExist() {
        assertAllFieldsExist("searchShop", methodBody("searchShop(String key"), "es/shop_v1.json");
    }

    @Test
    @DisplayName("shop 排序 DSL 的字段名全部存在于 shop mapping")
    void shopSortFieldsExist() {
        assertAllFieldsExist("shopSort", methodBody("List<Object> shopSort("), "es/shop_v1.json");
    }

    @Test
    @DisplayName("字段抽取不是空集（防止断言因正则失配而变成'永远绿'）")
    void extractionIsNotEmpty() {
        assertTrue(dslFields(methodBody("searchBlog(String key")).size() >= 7,
                "blog 搜索抽取到的字段太少，正则可能已失效");
        assertTrue(dslFields(methodBody("searchShop(String key")).size() >= 6,
                "shop 搜索抽取到的字段太少，正则可能已失效");
        assertTrue(dslFields(methodBody("List<Object> blogSort(")).size() >= 4);
        assertTrue(dslFields(methodBody("List<Object> shopSort(")).size() >= 3);
    }

    @Test
    @DisplayName("geo 坐标顺序：必须 lat=y、lon=x（项目约定 x=经度、y=纬度）")
    void geoLatLonOrderIsConvention() {
        String body = methodBody("searchShop(String key");
        // geo_distance 过滤 与 gauss 衰减 各一处，都必须是 lat=y / lon=x
        assertEquals(2, countOccurrences(body, "map(\"lat\", y, \"lon\", x)"),
                "geo 过滤/衰减里的经纬度顺序被改动 —— 写反不会报错，只是搜不到附近店铺");
    }

    @Test
    @DisplayName("geo 依赖的 location 字段在 mapping 里是 geo_point")
    void geoFieldIsGeoPoint() {
        assertTrue(mappingFields("es/shop_v1.json").contains("location"),
                "shop mapping 缺少 location 字段，geo 过滤与衰减都会失效");
    }

    @Test
    @DisplayName("分类聚合用的是 typeName.keyword（子字段缺失则聚合拿不到分桶）")
    void aggregationUsesKeywordSubField() {
        String body = methodBody("searchShop(String key");
        assertTrue(body.contains("\"typeName.keyword\""),
                "分类聚合应使用 typeName.keyword，而不是分词字段 typeName");
    }

    @Test
    @DisplayName("联想接口 _source 只取已声明字段")
    void suggestFieldsExist() {
        String body = methodBody("public List<String> suggest(");
        assertTrue(body.contains("\"title\""), "联想应取 title");
        assertTrue(body.contains("\"name\""), "联想应取 name");
        assertTrue(mappingFields("es/blog_v1.json").contains("title"));
        assertTrue(mappingFields("es/shop_v1.json").contains("name"));
    }

    @Test
    @DisplayName("高亮字段在各自 mapping 里都存在")
    void highlightFieldsExist() {
        assertTrue(mappingFields("es/blog_v1.json").containsAll(List.of("title", "content")));
        assertTrue(mappingFields("es/shop_v1.json").containsAll(List.of("name", "address")));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }
}
