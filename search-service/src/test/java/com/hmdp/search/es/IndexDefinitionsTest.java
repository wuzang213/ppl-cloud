package com.hmdp.search.es;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.search.config.SearchProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mapping 资源的加载、深拷贝与 IK 降级。
 * <p>
 * 这些都是"写错不报错"的地方：mapping JSON 少一个字段、降级把不该改的键改掉、
 * 深拷贝没生效导致模板被就地改坏 —— 三种都不会让服务起不来，只会让搜索结果悄悄不对。
 */
class IndexDefinitionsTest {

    private static IndexDefinitions definitions(boolean useIk) {
        SearchProperties properties = new SearchProperties();
        properties.setUseIk(useIk);
        return new IndexDefinitions(new ObjectMapper(), properties);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> field(Map<String, Object> body, String name) {
        Map<String, Object> mappings = (Map<String, Object>) body.get("mappings");
        Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");
        Map<String, Object> field = (Map<String, Object>) properties.get(name);
        assertTrue(field != null, "mapping 里缺少字段: " + name);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> settings(Map<String, Object> body) {
        return (Map<String, Object>) body.get("settings");
    }

    @Test
    @DisplayName("use-ik=true 时保留 ik_max_word / ik_smart")
    void ikIsPreservedWhenEnabled() {
        Map<String, Object> body = definitions(true).body("blog", "1s");
        assertEquals("text", field(body, "title").get("type"));
        assertEquals("ik_max_word", field(body, "title").get("analyzer"));
        assertEquals("ik_smart", field(body, "title").get("search_analyzer"));
    }

    @Test
    @DisplayName("use-ik=false 时 analyzer / search_analyzer 降级为 standard")
    void ikIsDowngradedWhenDisabled() {
        Map<String, Object> body = definitions(false).body("blog", "1s");
        assertEquals("standard", field(body, "title").get("analyzer"));
        assertEquals("standard", field(body, "title").get("search_analyzer"));
        // summary 只有 analyzer、content 只有 analyzer —— 都要被覆盖到
        assertEquals("standard", field(body, "summary").get("analyzer"));
        assertEquals("standard", field(body, "content").get("analyzer"));
    }

    @Test
    @DisplayName("降级只改 analyzer 两类键，type 与 keyword 子字段不受影响")
    void downgradeTouchesOnlyAnalyzerKeys() {
        Map<String, Object> body = definitions(false).body("blog", "1s");
        assertEquals("text", field(body, "title").get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> keyword = (Map<String, Object>) field(body, "title").get("fields");
        @SuppressWarnings("unchecked")
        Map<String, Object> keywordField = (Map<String, Object>) keyword.get("keyword");
        assertEquals("keyword", keywordField.get("type"));
        assertEquals(256, keywordField.get("ignore_above"));
    }

    @Test
    @DisplayName("店铺 mapping 的 location 必须是 geo_point（写错类型不会报错，只是搜不到位）")
    void shopLocationIsGeoPoint() {
        Map<String, Object> body = definitions(true).body("shop", "1s");
        assertEquals("geo_point", field(body, "location").get("type"));
        // typeName 需要 .keyword 子字段：分类聚合（左侧筛选器）按 typeName.keyword 分桶
        @SuppressWarnings("unchecked")
        Map<String, Object> typeNameFields = (Map<String, Object>) field(body, "typeName").get("fields");
        assertTrue(typeNameFields.containsKey("keyword"), "typeName 必须有 keyword 子字段，否则 aggs 拿不到分桶");
    }

    @Test
    @DisplayName("refresh_interval 被注入，日常 1s / 重建期 -1")
    void refreshIntervalIsInjected() {
        assertEquals("1s", settings(definitions(true).body("blog", "1s")).get("refresh_interval"));
        assertEquals("-1", settings(definitions(true).body("blog", "-1")).get("refresh_interval"));
        assertEquals("-1", settings(definitions(true).body("shop", "-1")).get("refresh_interval"));
    }

    @Test
    @DisplayName("其余 settings 保持不变（分片/副本/max_result_window）")
    void otherSettingsAreUntouched() {
        Map<String, Object> s = settings(definitions(true).body("blog", "1s"));
        assertEquals(1, s.get("number_of_shards"));
        assertEquals(0, s.get("number_of_replicas"));
        assertEquals(10000, s.get("max_result_window"));
    }

    @Test
    @DisplayName("body() 返回深拷贝：改坏返回值不影响下一次调用")
    void bodyReturnsDeepCopy() {
        IndexDefinitions definitions = definitions(true);
        Map<String, Object> first = definitions.body("blog", "1s");
        Map<String, Object> second = definitions.body("blog", "1s");
        assertNotSame(first, second);

        // 把第一次拿到的 body 彻底清空，再取一次应仍是完整的
        first.clear();
        Map<String, Object> third = definitions.body("blog", "1s");
        assertTrue(third.containsKey("mappings"), "模板被就地改坏了 —— deepCopy 失效");
        assertEquals("ik_max_word", field(third, "title").get("analyzer"));
    }

    @Test
    @DisplayName("两个类型互串：blog 的字段不会出现在 shop 的 body 里")
    void typesAreNotMixedUp() {
        Map<String, Object> blog = definitions(true).body("blog", "1s");
        Map<String, Object> shop = definitions(true).body("shop", "1s");
        assertEquals("ik_max_word", field(blog, "title").get("analyzer"));
        assertFalse(hasField(shop, "title"), "shop mapping 不该有 title 字段");
        assertFalse(hasField(blog, "location"), "blog mapping 不该有 location 字段");
        assertFalse(hasField(blog, "typeName"), "blog mapping 不该有 typeName 字段");
    }

    @SuppressWarnings("unchecked")
    private static boolean hasField(Map<String, Object> body, String name) {
        Map<String, Object> mappings = (Map<String, Object>) body.get("mappings");
        Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");
        return properties.containsKey(name);
    }
}
