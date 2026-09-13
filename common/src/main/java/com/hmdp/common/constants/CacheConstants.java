package com.hmdp.common.constants;

public class CacheConstants {
    public static final String CACHE_PURGE_HEADER = "X-Cache-Purge";
    public static final long SECOND = 1L;
    public static final long MINUTE = 60L;

    public static final String CACHE_SYNC_EXCHANGE = "cache.sync.direct";
    public static final String CACHE_SYNC_QUEUE = "cache.sync.queue";
    public static final String CACHE_SYNC_ROUTING_KEY = "cache.sync";

    // Fanout广播交换机
    public static final String CACHE_SYNC_FANOUT_EXCHANGE = "cache.sync.fanout";
    // 各个服务独立队列
    public static final String QUEUE_SHOP_CACHE = "cache.sync.shop.queue";
    public static final String QUEUE_BLOG_CACHE = "cache.sync.blog.queue";
    public static final String QUEUE_VOUCHER_CACHE = "cache.sync.voucher.queue";
    public static final String QUEUE_USER_CACHE = "cache.sync.user.queue";
}
