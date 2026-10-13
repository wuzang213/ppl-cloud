package com.hmdp.search.contract;

import com.hmdp.search.support.RepoFiles;
import com.hmdp.search.support.TextScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 红线 ④：<b>ES 版本三处对齐 7.12.1，且不得引入 Spring Data Elasticsearch</b>。
 * <p>
 * 背景：服务端 ES 是 <b>7.12.1</b>（含 IK 插件）。Boot 2.7 托管的 Spring Data ES 是 4.4.x，
 * 官方兼容矩阵写死「只兼容 ES 7.17.x」—— 一旦有人图省事把依赖换成
 * {@code spring-boot-starter-data-elasticsearch}，客户端会变成 7.17.6 去打 7.12.1 服务端。
 * 这类版本错配在简单查询上可能"看起来能用"，却在聚合/高亮/_update_by_query 上出各种诡异错误，
 * 而且**编译期与启动期都不报**。
 * <p>
 * 三处对齐 = ① 根 pom 的 {@code <elasticsearch.version>} 属性 ② search-service 的两个客户端
 * 依赖**不自己写 version**（从而继承 ①） ③ 不存在 Spring Data ES 依赖。
 */
class EsVersionContractTest {

    private static final String EXPECTED_VERSION = "7.12.1";

    private static final String ROOT_POM = RepoFiles.read(RepoFiles.repoFile("pom.xml"));

    private static final String SERVICE_POM = RepoFiles.read(RepoFiles.moduleFile("pom.xml"));

    /** 剥掉 XML 注释后的 pom —— 用于"是否真的声明了某依赖"这类断言（注释里会提到被否掉的方案） */
    private static final String ROOT_POM_CODE = TextScan.stripXmlComments(ROOT_POM);

    private static final String SERVICE_POM_CODE = TextScan.stripXmlComments(SERVICE_POM);

    private static String dependencyBlock(String pom, String artifactId) {
        String marker = "<artifactId>" + artifactId + "</artifactId>";
        int index = pom.indexOf(marker);
        assertTrue(index >= 0, "pom 里找不到依赖: " + artifactId);
        int start = pom.lastIndexOf("<dependency>", index);
        int end = pom.indexOf("</dependency>", index);
        assertTrue(start >= 0 && end > start, "依赖块结构异常: " + artifactId);
        return pom.substring(start, end);
    }

    @Test
    @DisplayName("根 pom 显式钉死 elasticsearch.version = 7.12.1")
    void rootPomPinsVersion() {
        assertTrue(ROOT_POM.contains("<elasticsearch.version>" + EXPECTED_VERSION + "</elasticsearch.version>"),
                "根 pom 必须显式声明 <elasticsearch.version>" + EXPECTED_VERSION + "</elasticsearch.version>");
    }

    @Test
    @DisplayName("该属性只出现一次（重复声明会让覆盖失效）")
    void versionPropertyIsDeclaredOnce() {
        assertEquals(1, count(ROOT_POM, "<elasticsearch.version>"),
                "<elasticsearch.version> 被声明了多次，版本决策就不再唯一");
    }

    @Test
    @DisplayName("search-service 的 es 客户端依赖不自己写 version（继承根 pom）")
    void servicePomInheritsVersion() {
        for (String artifactId : new String[]{
                "elasticsearch-rest-high-level-client", "elasticsearch-rest-client"}) {
            String block = dependencyBlock(SERVICE_POM_CODE, artifactId);
            assertFalse(block.contains("<version>"),
                    artifactId + " 不应自己写 <version>，否则与服务端版本脱钩");
        }
    }

    @Test
    @DisplayName("没有引入 spring-boot-starter-data-elasticsearch（会带来 7.17.x 客户端）")
    void springDataElasticsearchIsNotUsed() {
        // 断言前先剥掉注释：pom 的注释里**特意**写了"而不是 spring-boot-starter-data-elasticsearch"，
        // 那是要保留的设计说明，不是依赖声明。
        assertFalse(ROOT_POM_CODE.contains("spring-boot-starter-data-elasticsearch"),
                "根 pom 真的引入了 Spring Data ES —— 客户端会变成 7.17.x");
        assertFalse(SERVICE_POM_CODE.contains("spring-boot-starter-data-elasticsearch"),
                "search-service 真的引入了 Spring Data ES —— 客户端会变成 7.17.x");
    }

    @Test
    @DisplayName("用了 RestHighLevelClient / RestClient 这对官方客户端")
    void usesOfficialRestClient() {
        assertTrue(SERVICE_POM_CODE.contains("elasticsearch-rest-high-level-client"));
        assertTrue(SERVICE_POM_CODE.contains("elasticsearch-rest-client"));
    }

    @Test
    @DisplayName("pom 里对'为什么不用 Spring Data'留下了书面理由（防止后人'优化'掉）")
    void rationaleIsDocumented() {
        assertTrue(SERVICE_POM.contains("7.12.1"),
                "search-service/pom.xml 应写明服务端版本，否则后人无从判断为何要钉版");
        assertTrue(SERVICE_POM.contains("spring-boot-starter-data-elasticsearch")
                        || SERVICE_POM.contains("Spring Data"),
                "search-service/pom.xml 里应保留'为何不用 Spring Data ES'的说明注释");
    }

    @Test
    @DisplayName("ES 客户端只出现在 search-service，其它模块不得依赖它")
    void onlySearchServiceDependsOnEs() {
        for (String module : new String[]{"gateway-service", "user-service", "voucher-service",
                "blog-service", "shop-service", "common"}) {
            String raw = RepoFiles.read(RepoFiles.repoFile(module + "/pom.xml"));
            assertFalse(TextScan.stripXmlComments(raw).contains("elasticsearch-rest-high-level-client"),
                    module + " 不应依赖 ES 客户端（搜索是 search-service 的职责）");
        }
    }

    private static int count(String text, String needle) {
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
