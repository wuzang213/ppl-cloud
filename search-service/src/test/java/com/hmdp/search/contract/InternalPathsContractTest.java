package com.hmdp.search.contract;

import com.hmdp.search.support.RepoFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.StringReader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 红线 ⑤：<b>搜索的运维口子必须在网关内部接口黑名单里</b>。
 * <p>
 * 这里有个容易看漏的组合：{@code excludePaths} 里放行了 {@code /search/**}（搜索对游客开放），
 * 而 Ant 匹配下 {@code /search/**} <b>同时也覆盖了</b> {@code /search/admin/verify}、
 * {@code /search/admin/rebuild} 这些"一键全量重建 / 一致性修复"的运维口子。
 * 挡住它们的**只有** {@code internalPaths}（黑名单优先级高于白名单，命中即 403）。
 * <p>
 * 所以这两条断言是一对：白名单里有 {@code /search/**} ⇒ 黑名单里**必须**有 {@code /search/admin/**}。
 * 任何一边被单独改掉，都是"搜索运维接口对公网敞开"，而网关不会报任何错。
 * <p>
 * ⚠️ 本测试<strong>只读</strong>网关配置做断言，不修改（{@code docs/CONFIG-BOUNDARY.md} 的 Red Line）。
 */
class InternalPathsContractTest {

    private static final Map<String, Object> GATEWAY = loadGateway();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadGateway() {
        Object loaded = new Yaml().load(new StringReader(
                RepoFiles.read(RepoFiles.repoFile("gateway-service/src/main/resources/application.yaml"))));
        assertTrue(loaded instanceof Map, "网关 application.yaml 解析失败");
        return (Map<String, Object>) loaded;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(String dotted) {
        Object current = GATEWAY;
        for (String key : dotted.split("\\.")) {
            assertTrue(current instanceof Map, "路径不存在: " + dotted);
            current = ((Map<String, Object>) current).get(key);
        }
        assertTrue(current instanceof List, dotted + " 不是列表");
        return (List<String>) current;
    }

    private static Object navigate(String dotted) {
        Object current = GATEWAY;
        for (String key : dotted.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(key);
        }
        return current;
    }

    @Test
    @DisplayName("/search/admin/** 必须在 internalPaths（黑名单）里")
    void searchAdminIsInternal() {
        assertTrue(stringList("hmdp.auth.internalPaths").contains("/search/admin/**"),
                "/search/admin/** 不在黑名单里，任何人都能触发全量重建把 ES 打满");
    }

    @Test
    @DisplayName("反查口子 /blog/search-doc/** 与 /shop/search-doc/** 也必须在黑名单里")
    void reverseLookupPathsAreInternal() {
        List<String> internal = stringList("hmdp.auth.internalPaths");
        assertTrue(internal.contains("/blog/search-doc/**"), "缺少 /blog/search-doc/**");
        assertTrue(internal.contains("/shop/search-doc/**"), "缺少 /shop/search-doc/**");
    }

    @Test
    @DisplayName("黑名单就是这 3 条，没有被顺手塞进 /search/** 之类的大范围路径")
    void internalPathsAreExactlyTheThree() {
        Set<String> actual = new LinkedHashSet<>(stringList("hmdp.auth.internalPaths"));
        assertEquals(Set.of("/blog/search-doc/**", "/shop/search-doc/**", "/search/admin/**"), actual,
                "黑名单内容变化，请确认没有误伤正常接口");
    }

    @Test
    @DisplayName("白名单里有 /search/**（搜索对游客开放）")
    void searchIsPublic() {
        assertTrue(stringList("hmdp.auth.excludePaths").contains("/search/**"),
                "/search/** 不在白名单，游客搜索会 401");
    }

    @Test
    @DisplayName("白名单里不得出现 /search/admin/**（否则黑名单的覆盖关系被削弱）")
    void searchAdminIsNotWhitelisted() {
        assertFalse(stringList("hmdp.auth.excludePaths").contains("/search/admin/**"),
                "把 /search/admin/** 写进白名单是危险信号：白名单与黑名单同时命中时才靠优先级兜底");
    }

    @Test
    @DisplayName("白名单里也不得有裸的 /search/admin（无通配）等变体")
    void noAdminVariantsInExcludePaths() {
        for (String path : stringList("hmdp.auth.excludePaths")) {
            assertFalse(path.startsWith("/search/admin"),
                    "白名单里出现了 admin 变体: " + path);
        }
    }

    @Test
    @DisplayName("限流不许信任 X-Forwarded-For（可伪造，等于没限流）")
    void rateLimitDoesNotTrustForwardedFor() {
        Object value = navigate("hmdp.gateway.rate-limit.trust-x-forwarded-for");
        assertNotNull(value, "缺少 hmdp.gateway.rate-limit.trust-x-forwarded-for");
        assertEquals(Boolean.FALSE, value,
                "信任客户端可伪造的 X-Forwarded-For 会让按 IP 限流形同虚设");
    }

    @Test
    @DisplayName("白名单仍包含 2 条支付回调精确路径（确认搜索的契约测试没被误当成支付的白名单）")
    void payNotifyPathsStillPresent() {
        List<String> exclude = stringList("hmdp.auth.excludePaths");
        assertTrue(exclude.contains("/voucher-order/pay/notify/alipay"));
        assertTrue(exclude.contains("/voucher-order/pay/notify/wechat"));
        assertFalse(exclude.contains("/voucher-order/pay/notify/**"),
                "回调白名单不得退化成通配");
    }
}
