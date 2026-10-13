package com.hmdp.search.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** 搜索分页响应 */
@Data
public class SearchPageVO<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private long total;

    private List<T> hits;

    private boolean hasMore;

    /** 可选：聚合结果（如按 typeName 聚合，用于左侧筛选项） */
    private Map<String, Map<String, Long>> aggs;
}
