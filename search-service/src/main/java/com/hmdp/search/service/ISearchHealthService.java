package com.hmdp.search.service;

import java.util.Map;

/**
 * 健康检查：返回 ES 版本与索引状态（非敏感信息）。ES 不可用时抛出 RuntimeException。
 */
public interface ISearchHealthService {

    Map<String, Object> health();
}
