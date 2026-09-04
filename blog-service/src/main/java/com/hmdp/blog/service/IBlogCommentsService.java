package com.hmdp.blog.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.blog.domain.BlogComments;
import com.hmdp.common.domain.Result;


public interface IBlogCommentsService extends IService<BlogComments> {

    Result saveComment(BlogComments comment);

    Result queryByBlog(Long blogId, Integer current);

    Result deleteComment(Long id);
}
