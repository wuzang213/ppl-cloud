package com.hmdp.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/** 站内搜索服务（8085）。消费 {@code cache.sync.fanout} 广播 → 反查业务服务 {@code /search-doc/**} → 写 ES（业务侧零侵入）；
 * 重建用影子索引 + 别名原子切换 + 双写追平；查询为关键词检索 + 高亮 + 热度/距离排序 + 分页护栏。与 agent-service 的 RAG 互补不重复。 */
@SpringBootApplication
@EnableFeignClients(basePackages = "com.hmdp.api.client")
public class SearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
