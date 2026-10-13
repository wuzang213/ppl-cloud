package com.hmdp.search.service.impl;

import com.hmdp.search.service.ISearchSourceReader;
import com.hmdp.api.client.BlogClient;
import com.hmdp.api.client.ShopClient;
import com.hmdp.common.domain.Result;
import com.hmdp.common.search.BlogSearchDocDTO;
import com.hmdp.common.search.ShopSearchDocDTO;
import com.hmdp.search.config.SearchProperties;
import com.hmdp.search.converter.SearchDocConverter;
import com.hmdp.search.es.EsIndexNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;

/** 业务侧文档的统一读取口：全量重建、一致性校验、对账都从这里分页取数。唯一取数方式是调业务服务的内部接口（不直连业务库），保证全量/增量口径一致。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchSourceReaderImpl implements ISearchSourceReader {

    private final BlogClient blogClient;

    private final ShopClient shopClient;

    private final SearchDocConverter converter;

    private final SearchProperties properties;

    /** 取一页原始 DTO；调用失败直接抛异常（重建/对账都必须能感知失败，不能静默少读一页） */
    public List<Object> fetchPage(String type, int current, int size) {
        if (EsIndexNames.TYPE_BLOG.equals(type)) {
            Result<List<BlogSearchDocDTO>> result = blogClient.pageSearchDoc(current, size);
            requireSuccess(result != null && Boolean.TRUE.equals(result.getSuccess()), "分页反查文章", current);
            return result == null || result.getData() == null
                    ? List.of() : List.copyOf(result.getData());
        }
        Result<List<ShopSearchDocDTO>> result = shopClient.pageSearchDoc(current, size);
        requireSuccess(result != null && Boolean.TRUE.equals(result.getSuccess()), "分页反查店铺", current);
        return result == null || result.getData() == null
                ? List.of() : List.copyOf(result.getData());
    }

    public Object convert(String type, Object raw) {
        if (EsIndexNames.TYPE_BLOG.equals(type)) {
            return converter.toBlogDoc((BlogSearchDocDTO) raw);
        }
        return converter.toShopDoc((ShopSearchDocDTO) raw);
    }

    public Long idOf(Object raw) {
        return raw instanceof BlogSearchDocDTO
                ? ((BlogSearchDocDTO) raw).getId()
                : ((ShopSearchDocDTO) raw).getId();
    }

    /** 分页遍历业务侧全部 id（对账用）。每页回调一次，便于打印进度。 */
    public Set<Long> collectAllIds(String type, LongConsumer onPage) {
        int pageSize = properties.getRebuildPageSize();
        int current = 1;
        Set<Long> ids = new LinkedHashSet<>();
        while (true) {
            List<Object> docs = fetchPage(type, current, pageSize);
            if (docs.isEmpty()) {
                break;
            }
            List<Long> pageIds = new ArrayList<>(docs.size());
            for (Object raw : docs) {
                Long id = idOf(raw);
                if (id != null) {
                    ids.add(id);
                    pageIds.add(id);
                }
            }
            if (onPage != null) {
                // ⚠️ 不能写 pageIds.forEach(onPage)：List.forEach 要的是 Consumer<? super Long>，
                // 而 LongConsumer 是另一个函数式接口，二者不兼容。
                pageIds.forEach(id -> onPage.accept(id));
            }
            if (docs.size() < pageSize) {
                break;
            }
            current++;
        }
        return ids;
    }

    private void requireSuccess(boolean success, String action, int current) {
        if (!success) {
            throw new IllegalStateException(action + "失败，current=" + current
                    + "（业务服务降级或返回异常）；中止以免产出残缺数据");
        }
    }
}
