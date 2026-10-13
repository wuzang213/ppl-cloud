package com.hmdp.api.client;

import com.hmdp.api.client.fallback.BlogClientFallback;
import com.hmdp.api.config.DefaultFeignConfig;
import com.hmdp.common.domain.Result;
import com.hmdp.common.search.BlogSearchDocDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * blog-service 的「搜索文档反查」契约（供 search-service 建索引使用）。不要换成 {@code GET /blog/{id}}（会 {@code viewCounter.asyncIncr} 虚增浏览量）或 {@code GET /blog/page}（不调 queryBlogUser，拿不到作者），只走 {@code /blog/search-doc/**}。
 */
@FeignClient(value = "blog-service",
        configuration = DefaultFeignConfig.class,
        fallbackFactory = BlogClientFallback.class)
public interface BlogClient {

    /**
     * 反查单篇笔记的搜索文档（含冗余作者，不含浏览量副作用）。@return {@code success=true,data=null} 为该笔记确实已不存在（应删 ES 文档）；{@code success=false} 为调用失败（必须重试，绝不能当成"已删除"）。
     */
    @GetMapping("/blog/search-doc/{id}")
    Result<BlogSearchDocDTO> getSearchDoc(@PathVariable("id") Long id);

    /**
     * 全量初始化用的分页口子（含批量补齐的作者）。
     */
    @GetMapping("/blog/search-doc/page")
    Result<List<BlogSearchDocDTO>> pageSearchDoc(@RequestParam("current") Integer current,
                                                 @RequestParam("size") Integer size);
}
