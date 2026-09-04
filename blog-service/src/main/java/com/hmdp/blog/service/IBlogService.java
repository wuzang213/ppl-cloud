package com.hmdp.blog.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.blog.domain.Blog;
import com.hmdp.blog.domain.BlogDTO;
import com.hmdp.common.domain.Result;



public interface IBlogService extends IService<Blog> {

    Result queryBlogById(Long id);


    Result likeBlog(Long id);

    Result queryHotBlog(Integer current);

    Result queryBlogLikes(Long id);

    Result saveBlog(BlogDTO dto);

    Result queryBlogOfFollow(Long max, Integer offset);

    Result queryMyBlog(Integer current);

    Result queryBlogByUserId(Long id, Integer current);

    Result queryBlogByShopId(Long shopId, Integer current);

    Result queryBlogPage(Integer current, Integer size);
}
