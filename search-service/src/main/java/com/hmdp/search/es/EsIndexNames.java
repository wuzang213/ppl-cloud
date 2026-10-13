package com.hmdp.search.es;

/**
 * 索引与别名命名。对外一律用别名，物理索引带版本号 —— 重建时切别名即可，不用改代码、不用重启（代码里只认别名）。
 */
public final class EsIndexNames {

    private EsIndexNames() {
    }

    public static final String TYPE_BLOG = "blog";
    public static final String TYPE_SHOP = "shop";

    /** 对外查询/写入用的别名 */
    public static final String BLOG_ALIAS = "hmdp_blog";
    public static final String SHOP_ALIAS = "hmdp_shop";

    /** 物理索引前缀，完整名形如 hmdp_blog_v2 */
    public static final String BLOG_INDEX_PREFIX = "hmdp_blog_v";
    public static final String SHOP_INDEX_PREFIX = "hmdp_shop_v";

    public static String aliasOf(String type) {
        return TYPE_BLOG.equals(type) ? BLOG_ALIAS : SHOP_ALIAS;
    }

    public static String indexPrefixOf(String type) {
        return TYPE_BLOG.equals(type) ? BLOG_INDEX_PREFIX : SHOP_INDEX_PREFIX;
    }

    public static String mappingResourceOf(String type) {
        return TYPE_BLOG.equals(type) ? "es/blog_v1.json" : "es/shop_v1.json";
    }
}
