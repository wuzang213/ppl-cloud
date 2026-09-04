package com.hmdp.common.constants;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop:type:";
    public static final Long CACHE_SHOP_TYPE_TTL = 30L;

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String FEED_PULL_KEY = "feed:pull:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String VIEW_SHOP_KEY = "view:shop:";
    public static final String VIEW_BLOG_KEY = "view:blog:";
    public static final String USER_SIGN_KEY = "sign:";

    public static final String CACHE_VOUCHER_LIST_KEY = "cache:voucher:list:";
    public static final Long CACHE_VOUCHER_LIST_TTL = 10L;
    public static final String CACHE_BLOG_KEY = "cache:blog:";
    public static final Long CACHE_BLOG_TTL = 60L;
    public static final String CACHE_BLOG_HOT_KEY = "cache:blog:hot:";
    public static final Long CACHE_BLOG_HOT_TTL = 5L;

    public static final String FAVORITE_BLOG_KEY = "favorite:blog:";
    public static final String FAVORITE_SHOP_KEY = "favorite:shop:";
    public static final String FOLLOW_KEY = "follows:";
    public static final String REFRESH_TOKEN_KEY = "login:refresh:";
    public static final String REFRESH_INDEX_KEY = "login:refresh:index:";
    public static final String TOKEN_VERSION_KEY = "login:token-version:";
}
