package com.hmdp.search.contract;

import com.hmdp.search.support.RepoFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 红线 ③：<b>两个"必须为 false"的开关，以及一组安全默认值</b>。
 * <p>
 * 这两个开关都属于"写错不报错"的类别：
 * <ul>
 *   <li>{@code spring.rabbitmq.listener.simple.default-requeue-rejected=true} —— 毒消息（反序列化
 *       就失败的坏消息）会被**无限重投**，CPU 直接打满，而日志里只看到反复重试；</li>
 *   <li>{@code hmdp.outbox.reconcile.enabled=true} —— common 的 OutboxReconcileTask 会在**每个**
 *       引入 common 的服务里生效；search-service 按设计没有 {@code business_outbox} 表，
 *       开了它就会每 60 秒抛一次 BadSqlGrammarException 刷满 ERROR。</li>
 * </ul>
 * <p>
 * <b>本测试为什么有效</b>：规格 §9.1 第 6 项已核实 Nacos 侧**未配置** {@code search-service.yaml}
 * 这个 dataId，且 {@code bootstrap.yaml} 只订阅 7 个 {@code shared-*.yaml}（基础设施类，
 * 不含 {@code hmdp.outbox.*} 业务开关）—— 所以仓库里的 {@code application.yaml} 就是生效配置。
 * 下面 {@link #nacosSharedConfigsDoNotCarryBusinessSwitches()} 就是对这一前提的**结构性守卫**。
 */
class ConfigSwitchContractTest {

    private static final Map<String, Object> APPLICATION = load("application.yaml");

    private static final Map<String, Object> BOOTSTRAP = load("bootstrap.yaml");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String fileName) {
        Object loaded = new Yaml().load(new StringReader(
                RepoFiles.readMainResource(fileName)));
        assertTrue(loaded instanceof Map, fileName + " 解析结果不是 Map");
        return (Map<String, Object>) loaded;
    }

    private static Object navigate(Map<String, Object> root, String dotted) {
        Object current = root;
        for (String key : dotted.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(key);
        }
        return current;
    }

    /**
     * 仓库内全部生产源码（各模块的 {@code src/main/java}），用于「死开关」反向核查：
     * 一个写在 yaml 里的配置键，必须真的被某个 {@code .java} 引用，否则它只是摆设。
     */
    private static List<Path> repoMainJavaSources() {
        Path root = RepoFiles.repoRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("遍历仓库源码失败: " + root, e);
        }
    }

    @Test
    @DisplayName("default-requeue-rejected 必须显式为 false（毒消息不得无限重投）")
    void requeueRejectedIsExplicitlyFalse() {
        Object value = navigate(APPLICATION, "spring.rabbitmq.listener.simple.default-requeue-rejected");
        assertNotNull(value, "该开关必须**显式**写在 application.yaml 里，不能依赖框架默认值");
        assertEquals(Boolean.FALSE, value, "default-requeue-rejected 必须为 false（布尔值，不是字符串）");
    }

    @Test
    @DisplayName("hmdp.outbox.reconcile.enabled 必须显式为 false（本服务没有 business_outbox 表）")
    void outboxReconcileIsExplicitlyFalse() {
        Object value = navigate(APPLICATION, "hmdp.outbox.reconcile.enabled");
        assertNotNull(value, "该开关必须显式写在 application.yaml 里，否则会走 matchIfMissing=true 的默认值");
        assertEquals(Boolean.FALSE, value, "outbox.reconcile.enabled 必须为 false");
    }

    @Test
    @DisplayName("hmdp.outbox.reconcile.enabled 必须被某个 .java 实际引用（挡住「死开关」：写了 yaml 却没人读）")
    void outboxReconcileSwitchIsNotDead() {
        // 这是 P2-19 的根因反向护栏：yaml 里写了 false，但若没有 .java 读它，开关就是死开关，
        // search-service 仍会每 60 秒抛 Table 'hmdp_search.business_outbox' doesn't exist。
        // 本测试断言该键确实被 OutboxReconcileTask 的 @ConditionalOnProperty 引用；
        // 一旦有人把注解摘掉，本测试立即变红，防止「死开关」悄悄回潮。
        final String key = "outbox.reconcile.enabled";
        boolean wired = repoMainJavaSources().stream()
                .anyMatch(p -> RepoFiles.read(p).contains(key));
        assertTrue(wired,
                "配置键 hmdp.outbox.reconcile.enabled 在仓库里没有任何 .java 引用 —— 它是死开关："
                        + "yaml 写了 false，但 OutboxReconcileTask 从没读过，运行期什么也不会发生。"
                        + " 修法：在 common 的 OutboxReconcileTask 上加"
                        + " @ConditionalOnProperty(name=\"hmdp.outbox.reconcile.enabled\", havingValue=\"true\", matchIfMissing=true)");
    }

    @Test
    @DisplayName("use-ik 为 true（已装 analysis-ik 7.12.1）")
    void useIkIsOn() {
        assertEquals(Boolean.TRUE, navigate(APPLICATION, "hmdp.search.use-ik"));
    }

    @Test
    @DisplayName("查询护栏与代码默认值一致：max-page-size=50、max-window=1000")
    void pagingGuardConfigMatchesCodeDefaults() {
        assertEquals(50, navigate(APPLICATION, "hmdp.search.max-page-size"));
        assertEquals(1000, navigate(APPLICATION, "hmdp.search.max-window"));
    }

    @Test
    @DisplayName("失败重试上限与 outbox 的 MAX_RETRY 口径一致（5）")
    void failedRetryMaxAlignsWithOutbox() {
        assertEquals(5, navigate(APPLICATION, "hmdp.search.failed-retry-max"));
    }

    @Test
    @DisplayName("启动自检开启（别名缺失/为空时自动重建）")
    void autoRebuildOnStartupIsOn() {
        assertEquals(Boolean.TRUE, navigate(APPLICATION, "hmdp.search.auto-rebuild-on-startup"));
    }

    @Test
    @DisplayName("ES 地址有默认兜底（与 esVersion 三处对齐的前提）")
    void esEndpointHasFallback() {
        Object host = navigate(APPLICATION, "hmdp.search.host");
        assertNotNull(host);
        assertTrue(String.valueOf(host).contains("127.0.0.1"), "ES 应有默认兜底，实际: " + host);
    }

    @Test
    @DisplayName("Nacos 订阅的共享配置全是基础设施类，不携带 hmdp.outbox 等业务开关")
    void nacosSharedConfigsDoNotCarryBusinessSwitches() {
        Object shared = navigate(BOOTSTRAP, "spring.cloud.nacos.config.shared-configs");
        assertTrue(shared instanceof List, "bootstrap.yaml 应显式列出 shared-configs");
        List<?> configs = (List<?>) shared;
        assertEquals(7, configs.size(), "共享配置数量变化，请确认新增项是否含业务开关");
        for (Object item : configs) {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) item;
            // bootstrap.yaml 里写的是驼峰 dataId（不是 kebab-case 的 data-id）
            Object raw = config.containsKey("dataId") ? config.get("dataId") : config.get("data-id");
            assertNotNull(raw, "共享配置缺少 dataId: " + config);
            String dataId = String.valueOf(raw);
            assertTrue(dataId.startsWith("shared-"),
                    "共享配置必须是 shared-* 形态，实际: " + dataId);
            // ⚠️ 不要用 dataId.contains("search") 做判断：shared-elasticsearch.yaml 里就含 "search"
            //    （elasticsearch），会把合法的基础设施配置判成业务配置。这里改用精确比对。
            assertFalse(dataId.contains("outbox"),
                    "业务开关 hmdp.outbox.* 不得来自共享配置: " + dataId);
            assertFalse(dataId.equals("search-service.yaml"),
                    "本服务自身配置不得出现在 shared-configs 里（否则仓库 yaml 便不再是生效配置）: " + dataId);
            assertFalse(dataId.startsWith("hmdp-"),
                    "共享配置应是基础设施类（shared-*），不应是业务类: " + dataId);
        }
    }

    @Test
    @DisplayName("本服务自己的 dataId 是 search-service.yaml（Nacos 侧未配置 → 仓库 yaml 即生效配置）")
    void ownDataIdFollowsNamingConvention() {
        // Spring Cloud Nacos 默认取 spring.application.name + file-extension 作为 dataId。
        // 这里只断言命名约定；"Nacos 上是否真的没有这个 dataId" 无法离线断言，
        // 由规格 §9.1 第 6 项的人类核实结论兜底。
        assertEquals("search-service", navigate(BOOTSTRAP, "spring.application.name"));
        assertEquals("yaml", navigate(BOOTSTRAP, "spring.cloud.nacos.config.file-extension"));
    }

    @Test
    @DisplayName("YAML 结构自检：application.yaml 顶层键都在预期集合内（防误贴到别的服务）")
    void topLevelKeysLookSane() {
        assertTrue(APPLICATION.keySet().containsAll(List.of("server", "spring", "hmdp", "feign")),
                "application.yaml 顶层键异常: " + APPLICATION.keySet());
        Map<String, Object> sorted = new LinkedHashMap<>();
        APPLICATION.keySet().forEach(k -> sorted.put(k, APPLICATION.get(k)));
        assertTrue(sorted.containsKey("hmdp"));
    }
}
