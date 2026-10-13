package com.hmdp.search.es;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 索引/别名命名契约。
 * <p>
 * 为什么值得一组测试：本方案"重建时只切别名、不改代码不重启"的能力，
 * 完全建立在「业务代码里只出现别名、物理索引名带版本号」这条约定上。
 * 一旦有人把别名改成带版本号的形态（或让别名等于物理索引名），
 * 重建切别名就不再生效，而**任何编译/启动检查都不会报错** —— 搜索只是悄悄查到旧数据。
 */
class EsIndexNamesTest {

    @Test
    @DisplayName("别名是 hmdp_<type>，不带版本号")
    void aliasIsVersionless() {
        assertEquals("hmdp_blog", EsIndexNames.aliasOf("blog"));
        assertEquals("hmdp_shop", EsIndexNames.aliasOf("shop"));
        assertTrue(EsIndexNames.BLOG_ALIAS.startsWith("hmdp_"));
        assertTrue(EsIndexNames.SHOP_ALIAS.startsWith("hmdp_"));
    }

    @Test
    @DisplayName("别名里不得出现版本号（否则切别名失效）")
    void aliasMustNotCarryVersion() {
        // 形态即契约：hmdp_blog / hmdp_shop —— 若变成 hmdp_blog_v1，命名空间就不对了
        assertFalse(EsIndexNames.BLOG_ALIAS.matches(".*_v\\d+$"), "别名不能带版本后缀: " + EsIndexNames.BLOG_ALIAS);
        assertFalse(EsIndexNames.SHOP_ALIAS.matches(".*_v\\d+$"), "别名不能带版本后缀: " + EsIndexNames.SHOP_ALIAS);
    }

    @Test
    @DisplayName("物理索引前缀以 _v 结尾，可拼出版本号形态")
    void indexPrefixProducesVersionedName() {
        assertEquals("hmdp_blog_v", EsIndexNames.indexPrefixOf("blog"));
        assertEquals("hmdp_shop_v", EsIndexNames.indexPrefixOf("shop"));
        // 完整物理索引名形如 hmdp_blog_v2
        assertTrue((EsIndexNames.indexPrefixOf("blog") + 2).matches("^hmdp_blog_v\\d+$"));
        assertTrue((EsIndexNames.indexPrefixOf("shop") + 10).matches("^hmdp_shop_v\\d+$"));
    }

    @Test
    @DisplayName("别名与物理索引前缀不是同一个字符串")
    void aliasAndPrefixAreDistinct() {
        assertNotEquals(EsIndexNames.BLOG_ALIAS, EsIndexNames.BLOG_INDEX_PREFIX);
        assertNotEquals(EsIndexNames.SHOP_ALIAS, EsIndexNames.SHOP_INDEX_PREFIX);
    }

    @Test
    @DisplayName("两个类型互不相等，防止复制粘贴把 blog 写成 shop")
    void blogAndShopAreDistinct() {
        assertNotEquals(EsIndexNames.BLOG_ALIAS, EsIndexNames.SHOP_ALIAS);
        assertNotEquals(EsIndexNames.BLOG_INDEX_PREFIX, EsIndexNames.SHOP_INDEX_PREFIX);
        assertNotEquals(EsIndexNames.TYPE_BLOG, EsIndexNames.TYPE_SHOP);
    }

    @Test
    @DisplayName("mapping 资源名与类型一一对应，且确实是 classpath 上的文件")
    void mappingResourceMatchesType() {
        assertEquals("es/blog_v1.json", EsIndexNames.mappingResourceOf("blog"));
        assertEquals("es/shop_v1.json", EsIndexNames.mappingResourceOf("shop"));
        assertTrue(getClass().getClassLoader().getResource(EsIndexNames.mappingResourceOf("blog")) != null,
                "es/blog_v1.json 必须在 classpath 上");
        assertTrue(getClass().getClassLoader().getResource(EsIndexNames.mappingResourceOf("shop")) != null,
                "es/shop_v1.json 必须在 classpath 上");
    }

    @Test
    @DisplayName("未知 type 一律回退到 shop（记录实际行为，见 EsIndexNames.aliasOf）")
    void unknownTypeFallsBackToShop() {
        // 这是现有实现的真实行为：三分支都写成 "TYPE_BLOG.equals(type) ? blog : shop"。
        // 用断言把它钉住，避免将来"顺手改成抛异常"时无人知晓（调用方都传常量，实际不会触发）。
        assertEquals(EsIndexNames.SHOP_ALIAS, EsIndexNames.aliasOf("whatever"));
        assertEquals(EsIndexNames.SHOP_INDEX_PREFIX, EsIndexNames.indexPrefixOf("whatever"));
        assertEquals("es/shop_v1.json", EsIndexNames.mappingResourceOf("whatever"));
    }
}
