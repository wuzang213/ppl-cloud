package com.hmdp.blog.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.blog.domain.Blog;
import com.hmdp.blog.domain.BlogDTO;
import com.hmdp.common.domain.Result;



public interface IBlogService extends IService<Blog> {

    Result queryBlogById(Long id);

    /** 查询笔记详情，并决定是否累加浏览量；{@code countView=false} 供搜索索引同步做无副作用反查（否则虚增浏览量）。
     * 用 service 层参数而非 {@code ?internal=true}：{@code /blog/**} 是公网路径，query param 被爬虫发现等于放任关掉浏览统计。 */
    Result queryBlogById(Long id, boolean countView);

    /** 搜索索引专用：反查单篇笔记（不计数、含冗余作者）。
     * @return {@code Result.ok(null)} = 笔记确实不存在（调用方据此删 ES 文档）；「调用失败」由 Feign 降级返回 {@code Result.fail}。 */
    Result queryBlogSearchDoc(Long id);

    /**
     * 搜索索引专用：全量初始化分页口子（含批量补齐的作者，与增量路径同一组装出口）。
     */
    Result querySearchDocPage(Integer current, Integer size);

    Result likeBlog(Long id);

    Result queryHotBlog(Integer current);

    Result queryBlogLikes(Long id);

    Result saveBlog(BlogDTO dto);

    Result queryBlogOfFollow(Long max, Integer offset);

    Result queryMyBlog(Integer current);

    Result queryBlogByUserId(Long id, Integer current);

    Result queryBlogByShopId(Long shopId, Integer current);

    Result queryBlogPage(Integer current, Integer size);

    Result updateBlog(BlogDTO dto);

    Result deleteBlog(Long id);
}
