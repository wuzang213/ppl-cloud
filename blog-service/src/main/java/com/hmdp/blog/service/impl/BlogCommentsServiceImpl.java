package com.hmdp.blog.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.blog.constants.BlogConstants;
import com.hmdp.blog.domain.Blog;
import com.hmdp.blog.domain.BlogComments;
import com.hmdp.blog.mapper.BlogCommentsMapper;
import com.hmdp.blog.mapper.BlogMapper;
import com.hmdp.blog.service.IBlogCommentsService;
import com.hmdp.common.constants.MqConstants;
import com.hmdp.common.domain.NoticeMessage;
import com.hmdp.common.exception.ForbiddenException;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.outbox.OutboxWriter;
import com.hmdp.common.constants.SystemConstants;
import com.hmdp.common.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments>
        implements IBlogCommentsService {

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private OutboxWriter outboxWriter;

    @Override
    @Transactional
    public Result saveComment(BlogComments comment) {
        // 1.从ThreadLocal拿当前登录用户
        UserDTO user = UserHolder.getUser();
        comment.setUserId(user.getId());
        // 2.保存评论到数据库
        save(comment);
        // 3.查询这条评论所属的博客
        Blog blog = blogMapper.selectById(comment.getBlogId());
        // 4.博客存在 并且 评论人 != 博客作者
        if (blog != null && !blog.getUserId().equals(user.getId())) {
            // 评论通知改为写 outbox：由 Canal + MQ 可靠投递到通知交换机，
            // 有记录可对账重试，不再依赖请求线程内的 afterCommit（失败即丢失）
            NoticeMessage notice = new NoticeMessage(blog.getUserId(), BlogConstants.NOTICE_TYPE_COMMENT,
                    BlogConstants.NOTICE_COMMENT_CONTENT, comment.getBlogId());
            outboxWriter.write("BLOG_COMMENT", comment.getId(), "COMMENT_NOTICE",
                    MqConstants.NOTICE_DIRECT_EXCHANGE, MqConstants.NOTICE_ROUTING_KEY, notice);
        }
        return Result.ok(comment.getId());
    }

    @Override
    public Result queryByBlog(Long blogId, Integer current) {
        Page<BlogComments> page = query()
                .eq("blog_id", blogId)
                .orderByAsc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @Override
    public Result deleteComment(Long id) {
        BlogComments comment = getById(id);
        if (comment == null || !comment.getUserId().equals(UserHolder.getUser().getId())) {
            throw new ForbiddenException(BlogConstants.COMMENT_DELETE_FORBIDDEN);
        }
        removeById(id);
        return Result.ok();
    }
}
