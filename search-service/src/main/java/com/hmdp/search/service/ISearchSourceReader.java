package com.hmdp.search.service;

import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;

/** 业务侧文档的统一读取口：全量重建、一致性校验、对账都从这里分页取数。唯一取数方式是调业务服务的内部接口（不直连业务库），保证全量/增量口径一致。 */
public interface ISearchSourceReader {

    List<Object> fetchPage(String type, int current, int size);

    Object convert(String type, Object raw);

    Long idOf(Object raw);

    /** 分页遍历业务侧全部 id（对账用），每页回调一次便于打印进度 */
    Set<Long> collectAllIds(String type, LongConsumer onPage);
}
