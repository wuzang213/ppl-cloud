package com.hmdp.search.es;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.search.config.SearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** 索引 mapping 的唯一来源：读取 {@code resources/es/*.json}。
 * 所有 ES 交互（mapping、查询 DSL、_update_by_query 脚本、_aliases）都是手写 JSON 放在文件里，可直接拿去 {@code curl} 验证。 */
@Slf4j
@Component
public class IndexDefinitions {

    private final ObjectMapper mapper;

    private final boolean useIk;

    private final Map<String, Object> blogBody;

    private final Map<String, Object> shopBody;

    public IndexDefinitions(@Qualifier("esObjectMapper") ObjectMapper mapper, SearchProperties properties) {
        this.mapper = mapper;
        this.useIk = properties.isUseIk();
        this.blogBody = load(EsIndexNames.TYPE_BLOG);
        this.shopBody = load(EsIndexNames.TYPE_SHOP);
        if (!useIk) {
            log.warn("【降级】hmdp.search.use-ik=false，IK 分词器不可用，已把 mapping 里的 "
                    + "ik_max_word / ik_smart 降级为 standard。中文会退化为单字切分，搜索质量下降，请尽快修复插件。");
        }
    }

    /**
     * 取建索引用的完整 body（settings + mappings）。{@code refreshInterval}：重建期传 {@code -1} 关刷新提吞吐、日常传 {@code 1s}；返回深拷贝，调用方改动不影响模板。
     */
    public Map<String, Object> body(String type, String refreshInterval) {
        Map<String, Object> src = EsIndexNames.TYPE_BLOG.equals(type) ? blogBody : shopBody;
        Map<String, Object> copy = deepCopy(src);
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) copy.computeIfAbsent("settings", k -> new LinkedHashMap<>());
        settings.put("refresh_interval", refreshInterval);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load(String type) {
        String resource = EsIndexNames.mappingResourceOf(type);
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            Map<String, Object> body = mapper.readValue(in, new TypeReference<Map<String, Object>>() {
            });
            if (!useIk) {
                downgradeAnalyzer(body);
            }
            return body;
        } catch (Exception e) {
            throw new IllegalStateException("加载 ES 索引定义失败: " + resource, e);
        }
    }

    private Map<String, Object> deepCopy(Map<String, Object> src) {
        try {
            return mapper.readValue(mapper.writeValueAsString(src), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("复制 ES 索引定义失败", e);
        }
    }

    /**
     * IK -> standard 降级：递归替换 analyzer / search_analyzer 里以 ik_ 开头的值。
     */
    @SuppressWarnings("unchecked")
    private void downgradeAnalyzer(Object node) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (("analyzer".equals(key) || "search_analyzer".equals(key))
                        && value instanceof String && ((String) value).startsWith("ik_")) {
                    entry.setValue("standard");
                } else {
                    downgradeAnalyzer(value);
                }
            }
        } else if (node instanceof Iterable) {
            for (Object item : (Iterable<Object>) node) {
                downgradeAnalyzer(item);
            }
        }
    }
}
