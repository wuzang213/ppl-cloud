package com.hmdp.search.service.impl;

import com.hmdp.search.service.ISearchIndexService;
import com.hmdp.search.service.RebuildRegistry;
import com.hmdp.api.client.BlogClient;
import com.hmdp.api.client.ShopClient;
import com.hmdp.common.domain.Result;
import com.hmdp.common.search.BlogSearchDocDTO;
import com.hmdp.common.search.ShopSearchDocDTO;
import com.hmdp.search.converter.SearchDocConverter;
import com.hmdp.search.doc.BlogDoc;
import com.hmdp.search.doc.ShopDoc;
import com.hmdp.search.es.EsClient;
import com.hmdp.search.es.EsIndexNames;
import com.hmdp.search.exception.SearchSyncException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 索引写入唯一出口：单文档 upsert / delete，以及冗余字段批量刷新。全量与增量都收敛到这里，保证写入一律以业务库真值为准、_id 用业务主键。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchIndexServiceImpl implements ISearchIndexService {

    // 事件常量见 ISearchIndexService（BLOG_INDEX_EVENTS / SHOP_INDEX_EVENTS）

    private final EsClient esClient;

    private final SearchDocConverter converter;

    private final RebuildRegistry rebuildRegistry;

    private final BlogClient blogClient;

    private final ShopClient shopClient;

    // ==================== 增量入口 ====================

    /** 按事件类型刷新一篇笔记的索引。@return true 表示事件被本服务处理了，false 表示与索引无关（调用方只打日志）。 */
    public boolean applyBlogEvent(String eventType, Long id) {
        if (eventType == null || !BLOG_INDEX_EVENTS.contains(eventType)) {
            return false;
        }
        if ("BLOG_DELETED".equals(eventType)) {
            // 删除事件不需要反查：outbox 与业务删除同事务，事件可见就意味着删除已提交。
            // 即便有一条更早的 UPDATED 消息乱序后到，它反查会 404，仍然收敛到"已删除"。
            writeDelete(EsIndexNames.TYPE_BLOG, id);
            return true;
        }
        apply(EsIndexNames.TYPE_BLOG, id);
        return true;
    }

    public boolean applyShopEvent(String eventType, Long id) {
        if (eventType == null || !SHOP_INDEX_EVENTS.contains(eventType)) {
            return false;
        }
        if ("SHOP_DELETED".equals(eventType)) {
            writeDelete(EsIndexNames.TYPE_SHOP, id);
            return true;
        }
        apply(EsIndexNames.TYPE_SHOP, id);
        return true;
    }

    /** 反查业务真值后写别名索引（+ 重建期间双写影子索引） */
    public void apply(String type, Long id) {
        Object doc = fetchDoc(type, id);
        if (doc == null) {
            writeDelete(type, id);
            return;
        }
        writeIndex(type, id, doc);
    }

    /** 只写入指定的物理索引（重建结束前的"重放"用它，避免把重建期间的变更重复写到旧索引上）。与 {@link #apply} 的差别仅在于目标索引，取数据的口径完全一致。 */
    public void applyToIndex(String type, Long id, String targetIndex) {
        Object doc = fetchDoc(type, id);
        String idStr = String.valueOf(id);
        if (doc == null) {
            doDelete(targetIndex, idStr);
        } else {
            doIndex(targetIndex, idStr, doc);
        }
    }

    // ==================== 冗余字段批量刷新 ====================

    /** 作者改名/换头像：刷新该用户名下所有文章的 {@code authorName/authorIcon}。
     * 用 {@code _update_by_query} 而非重建索引 —— 成本差 2~3 个数量级：重建 = 造影子索引 + 全量重灌 + 切别名（分钟级），这里 = 按 {@code authorId} 倒排定位后局部改字段（毫秒~秒级），且搜索全程可用。 */
    public long refreshBlogAuthor(Long userId, String authorName, String authorIcon) {
        Map<String, Object> params = new HashMap<>(2);
        params.put("n", authorName == null ? "" : authorName);
        params.put("i", authorIcon == null ? "" : authorIcon);
        Script script = new Script(ScriptType.INLINE, "painless",
                "ctx._source.authorName = params.n; ctx._source.authorIcon = params.i", params);
        try {
            long updated = esClient.updateByQuery(
                    EsIndexNames.BLOG_ALIAS, QueryBuilders.termQuery("authorId", userId), script);
            log.info("已刷新作者冗余字段，userId={}, 影响文档数={}", userId, updated);
            return updated;
        } catch (IOException e) {
            throw new SearchSyncException("刷新作者冗余字段失败，userId=" + userId, e);
        }
    }

    /** 分类改名：刷新该分类下所有店铺文档的 {@code typeName}。这是「分类不独立建索引」的代价项，但分类只有个位数、改名是运营级低频操作，一次 {@code _update_by_query} 完全够；
     * 若独立建索引则会丢掉"搜品类词出店"这条核心链路（店名往往不含品类词，靠的就是店铺文档里的 typeName 参与检索）。 */
    public long refreshShopTypeName(Long typeId, String typeName) {
        Script script = new Script(ScriptType.INLINE, "painless",
                "ctx._source.typeName = params.n", Map.of("n", typeName == null ? "" : typeName));
        try {
            long updated = esClient.updateByQuery(
                    EsIndexNames.SHOP_ALIAS, QueryBuilders.termQuery("typeId", typeId), script);
            log.info("已刷新分类名冗余字段，typeId={}, typeName={}, 影响文档数={}", typeId, typeName, updated);
            return updated;
        } catch (IOException e) {
            throw new SearchSyncException("刷新分类名冗余字段失败，typeId=" + typeId, e);
        }
    }

    // ==================== 内部：取数与写入 ====================

    /** 反查业务真值。@return 文档；{@code null} 表示业务侧确实不存在（调用方应删索引）；@throws SearchSyncException 调用失败（降级返回 success=false）—— 必须重试，绝不能当成"已删除"。 */
    private Object fetchDoc(String type, Long id) {
        if (EsIndexNames.TYPE_BLOG.equals(type)) {
            Result<BlogSearchDocDTO> result = blogClient.getSearchDoc(id);
            if (!succeeded(result)) {
                throw new SearchSyncException("反查文章搜索文档失败（降级或返回异常），blogId=" + id);
            }
            return result.getData() == null ? null : converter.toBlogDoc(result.getData());
        }
        Result<ShopSearchDocDTO> result = shopClient.getSearchDoc(id);
        if (!succeeded(result)) {
            throw new SearchSyncException("反查店铺搜索文档失败（降级或返回异常），shopId=" + id);
        }
        return result.getData() == null ? null : converter.toShopDoc(result.getData());
    }

    /** ⚠️ 这里必须只看 {@code success}：Feign 降级返回的是 {@code Result.fail(...)}、其 data 也是 null，若把 success=false 当成"文档不存在"，一次网络抖动就会把索引里的正常文档删掉。 */
    private boolean succeeded(Result<?> result) {
        return result != null && Boolean.TRUE.equals(result.getSuccess());
    }

    private void writeIndex(String type, Long id, Object doc) {
        String alias = EsIndexNames.aliasOf(type);
        String idStr = String.valueOf(id);
        doIndex(alias, idStr, doc);
        // ⚠️ 用一次查找代替 isRebuilding() + shadowIndex() 两次查找：两次查找之间存在窗口 —— 重建刚结束
        //    （contexts 被 remove）时第一次查 true、第二次拿 null，就会用 null 当索引名去写 ES；
        //    shadowIndex() 返回 null 本身就是"不在重建态"，一次调用即可，既消除 TOCTOU 也少一次查找。
        String shadow = rebuildRegistry.shadowIndex(type);
        if (shadow != null) {
            doIndex(shadow, idStr, doc);
            // 登记重放：本次扫描可能稍后用"快照旧值"覆盖影子索引里的这条，收尾时统一纠正
            rebuildRegistry.recordMutation(type, id);
        }
    }

    private void writeDelete(String type, Long id) {
        String alias = EsIndexNames.aliasOf(type);
        String idStr = String.valueOf(id);
        doDelete(alias, idStr);
        String shadow = rebuildRegistry.shadowIndex(type);
        if (shadow != null) {
            doDelete(shadow, idStr);
            // 同样要登记：若扫描在本次删除之前已把该文档读进快照，最终会把它又写回影子索引
            rebuildRegistry.recordMutation(type, id);
        }
    }

    private void doIndex(String index, String id, Object doc) {
        try {
            esClient.indexDoc(index, id, doc);
        } catch (IOException e) {
            throw new SearchSyncException("写入 ES 失败 index=" + index + ", id=" + id, e);
        }
    }

    private void doDelete(String index, String id) {
        try {
            esClient.deleteDoc(index, id);
        } catch (IOException e) {
            throw new SearchSyncException("删除 ES 文档失败 index=" + index + ", id=" + id, e);
        }
    }

    /** 供重建/对账复用的取数出口 */
    public BlogDoc fetchBlogDoc(Long id) {
        Object doc = fetchDoc(EsIndexNames.TYPE_BLOG, id);
        return doc == null ? null : (BlogDoc) doc;
    }

    public ShopDoc fetchShopDoc(Long id) {
        Object doc = fetchDoc(EsIndexNames.TYPE_SHOP, id);
        return doc == null ? null : (ShopDoc) doc;
    }
}
