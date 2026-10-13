package com.hmdp.search.service;

import com.hmdp.search.doc.BlogDoc;
import com.hmdp.search.doc.ShopDoc;

import java.util.Set;

/** 索引写入唯一出口：单文档 upsert / delete，以及冗余字段批量刷新。全量与增量都收敛到这里，保证写入一律以业务库真值为准、_id 用业务主键。 */
public interface ISearchIndexService {

    /** 会改变索引内容的 BLOG 事件（BLOG_LIKED/BLOG_NOTICE 走别的交换机，这里也显式挡掉） */
    Set<String> BLOG_INDEX_EVENTS = Set.of("BLOG_PUBLISHED", "BLOG_UPDATED", "BLOG_DELETED");

    /** 会改变索引内容的 SHOP 事件 */
    Set<String> SHOP_INDEX_EVENTS = Set.of("SHOP_CREATED", "SHOP_UPDATED", "SHOP_DELETED");

    boolean applyBlogEvent(String eventType, Long id);

    boolean applyShopEvent(String eventType, Long id);

    /** 反查业务真值后写别名索引（+ 重建期间双写影子索引） */
    void apply(String type, Long id);

    /** 只写入指定物理索引（重建结束前的"重放"用，避免把重建期间的变更重复写到旧索引） */
    void applyToIndex(String type, Long id, String targetIndex);

    long refreshBlogAuthor(Long userId, String authorName, String authorIcon);

    long refreshShopTypeName(Long typeId, String typeName);

    BlogDoc fetchBlogDoc(Long id);

    ShopDoc fetchShopDoc(Long id);
}
