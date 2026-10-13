package com.hmdp.search.contract;

import com.hmdp.search.support.RepoFiles;
import com.hmdp.search.support.TextScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 红线 ①：<b>业务代码里只能用别名，不能出现物理索引名</b>。
 * <p>
 * 为什么这是红线：本方案「重建时切别名、不改代码不重启」的能力，前提是代码只认别名。
 * 一旦有人在查询/写入路径里写了 {@code hmdp_blog_v2}，重建切完别名后代码仍打旧索引 ——
 * <b>不会报错</b>，只是用户看到的永远是重建前的旧数据。
 * <p>
 * 本测试读**源码文本**做断言（沿用 agent-service/app/tests/test_repo_contracts.py 的既有模式）。
 */
class AliasOnlyContractTest {

    /** 物理索引名 = 别名 + "_v" + 数字 */
    private static final Pattern PHYSICAL_INDEX_LITERAL = Pattern.compile("hmdp_(?:blog|shop)_v\\d+");

    private static final List<String> BUSINESS_CLASSES = List.of(
            "com/hmdp/search/service/impl/SearchQueryServiceImpl.java",
            "com/hmdp/search/service/impl/SearchIndexServiceImpl.java",
            "com/hmdp/search/service/impl/SearchIndexRebuildServiceImpl.java",
            "com/hmdp/search/service/impl/SearchVerifyServiceImpl.java",
            "com/hmdp/search/controller/SearchController.java",
            "com/hmdp/search/controller/SearchAdminController.java",
            "com/hmdp/search/listener/SearchIndexListener.java");

    @Test
    @DisplayName("全部生产代码的字符串字面量里不得出现物理索引名")
    void noPhysicalIndexLiteralAnywhere() {
        // 只看**字符串字面量**，不看注释：注释里写"完整名形如 hmdp_blog_v2"是好事（知识被记录），
        // 真正危险的是代码里出现这个字面量（重建切别名后会静默查旧索引）。
        List<String> offenders = new ArrayList<>();
        for (Path src : RepoFiles.mainJavaSources()) {
            for (String literal : TextScan.stringLiterals(RepoFiles.read(src))) {
                if (PHYSICAL_INDEX_LITERAL.matcher(literal).matches()) {
                    offenders.add(RepoFiles.moduleDir().relativize(src) + " -> \"" + literal + "\"");
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "代码里出现物理索引名字面量；重建切别名后会静默查到旧索引: " + offenders);
    }

    @Test
    @DisplayName("物理索引前缀只在 EsIndexNames 里声明（唯一真源）")
    void prefixDeclaredOnlyInEsIndexNames() {
        List<String> declaring = new ArrayList<>();
        for (Path src : RepoFiles.mainJavaSources()) {
            String text = RepoFiles.read(src);
            // 形如 "hmdp_blog_v" 的**字面量声明**（后面不跟数字）
            if (text.contains("\"hmdp_blog_v\"") || text.contains("\"hmdp_shop_v\"")) {
                declaring.add(RepoFiles.moduleDir().relativize(src).toString().replace('\\', '/'));
            }
        }
        assertEquals(List.of("src/main/java/com/hmdp/search/es/EsIndexNames.java"), declaring,
                "物理索引前缀被多处声明，改名时必然漏改一处");
    }

    @Test
    @DisplayName("EsIndexNames 的别名常量不带版本号")
    void aliasConstantsAreVersionless() {
        String source = RepoFiles.readMainJava("com/hmdp/search/es/EsIndexNames.java");
        List<String> literals = TextScan.stringLiterals(source);
        assertTrue(literals.contains("hmdp_blog"), "别名常量的字面量被改动: " + literals);
        assertTrue(literals.contains("hmdp_shop"), "别名常量的字面量被改动: " + literals);
        // 前缀字面量是 "hmdp_blog_v"（不含数字）；任何**带数字**的索引名都不该在这里出现
        for (String literal : literals) {
            assertFalse(PHYSICAL_INDEX_LITERAL.matcher(literal).matches(),
                    "EsIndexNames 里出现了带版本号的物理索引名字面量: " + literal);
        }
    }

    @Test
    @DisplayName("查询路径只认别名常量")
    void queryPathUsesAliases() {
        String source = RepoFiles.readMainJava("com/hmdp/search/service/impl/SearchQueryServiceImpl.java");
        assertTrue(source.contains("BLOG_ALIAS"), "查询路径没用 blog 别名");
        assertTrue(source.contains("SHOP_ALIAS"), "查询路径没用 shop 别名");
    }

    @Test
    @DisplayName("写入路径（增量同步）也只认别名")
    void writePathUsesAliases() {
        String source = RepoFiles.readMainJava("com/hmdp/search/service/impl/SearchIndexServiceImpl.java");
        assertTrue(source.contains("BLOG_ALIAS") || source.contains("aliasOf"),
                "增量写入没用别名，重建后新数据会写进旧索引");
    }

    @Test
    @DisplayName("重建路径通过 aliasOf / indexPrefixOf 取名字，不自己拼字符串")
    void rebuildPathUsesHelpers() {
        String source = RepoFiles.readMainJava("com/hmdp/search/service/impl/SearchIndexRebuildServiceImpl.java");
        assertTrue(source.contains("EsIndexNames.aliasOf("), "重建未通过 aliasOf 取别名");
        assertTrue(source.contains("EsIndexNames.indexPrefixOf("), "重建未通过 indexPrefixOf 取物理索引前缀");
    }

    @Test
    @DisplayName("每个业务类都确实存在（防止断言对象被改名后测试静默失效）")
    void businessClassesExist() {
        for (String relative : BUSINESS_CLASSES) {
            Path file = RepoFiles.moduleFile("src/main/java/" + relative);
            assertTrue(java.nio.file.Files.isRegularFile(file), "断言对象不存在: " + relative);
        }
    }
}
