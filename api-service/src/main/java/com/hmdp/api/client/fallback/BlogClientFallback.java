package com.hmdp.api.client.fallback;

import com.hmdp.api.client.BlogClient;
import com.hmdp.common.domain.Result;
import com.hmdp.common.search.BlogSearchDocDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;

/**
 * ⚠️ 降级必须与「查不到」区分开：这里返回 {@code Result.fail(...)}（success=false），调用方见到 success=false 必须重试；若返回 {@code Result.ok(null)}，search-service 会把它当成「笔记已被删除」而误删索引。
 */
@Slf4j
public class BlogClientFallback implements FallbackFactory<BlogClient> {

    @Override
    public BlogClient create(Throwable cause) {
        return new BlogClient() {
            @Override
            public Result<BlogSearchDocDTO> getSearchDoc(Long id) {
                log.error("远程调用 BlogClient#getSearchDoc 失败，blogId={}", id, cause);
                return Result.fail("反查文章搜索文档失败");
            }

            @Override
            public Result<List<BlogSearchDocDTO>> pageSearchDoc(Integer current, Integer size) {
                log.error("远程调用 BlogClient#pageSearchDoc 失败，current={}, size={}", current, size, cause);
                return Result.fail("分页反查文章搜索文档失败");
            }
        };
    }
}
