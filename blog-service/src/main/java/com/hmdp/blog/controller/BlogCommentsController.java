package com.hmdp.blog.controller;

import com.hmdp.blog.domain.BlogComments;
import com.hmdp.blog.service.IBlogCommentsService;
import com.hmdp.common.domain.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Tag(name = "博客评论服务", description = "评论发布、查询、删除")
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService commentService;

    @Operation(summary = "发表评论")
    @PostMapping
    public Result save(@RequestBody BlogComments comment) {
        return commentService.saveComment(comment);
    }

    @Operation(summary = "查询博客评论列表")
    @GetMapping("/blog/{blogId}")
    public Result list(@PathVariable("blogId") @Parameter(description = "博客ID") Long blogId,
                       @RequestParam(value = "current", defaultValue = "1") @Parameter(description = "页码") Integer current) {
        return commentService.queryByBlog(blogId, current);
    }

    @Operation(summary = "删除评论")
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable("id") @Parameter(description = "评论ID") Long id) {
        return commentService.deleteComment(id);
    }
}