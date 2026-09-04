package com.hmdp.blog.controller;

import com.hmdp.blog.domain.Blog;
import com.hmdp.blog.domain.BlogDTO;
import com.hmdp.blog.service.IBlogService;
import com.hmdp.common.domain.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Tag(name = "博客服务", description = "探店笔记发布、查询、点赞、Feed流")
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    @Operation(summary = "发布探店笔记")
    @PostMapping
    public Result saveBlog(@RequestBody BlogDTO dto) {
        return blogService.saveBlog(dto);
    }

    @Operation(summary = "点赞或取消点赞笔记")
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") @Parameter(description = "笔记ID") Long id) {
        return blogService.likeBlog(id);
    }

    @Operation(summary = "查询我的笔记")
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") @Parameter(description = "页码") Integer current) {
        return blogService.queryMyBlog(current);
    }

    @Operation(summary = "查询热门笔记")
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") @Parameter(description = "页码") Integer current) {
        return blogService.queryHotBlog(current);
    }

    @Operation(summary = "查询笔记详情")
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") @Parameter(description = "笔记ID") Long id) {
        return blogService.queryBlogById(id);
    }

    @Operation(summary = "查询笔记点赞用户Top5")
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") @Parameter(description = "笔记ID") Long id) {
        return blogService.queryBlogLikes(id);
    }

    @Operation(summary = "按用户查询笔记")
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") @Parameter(description = "页码") Integer current,
            @RequestParam("id") @Parameter(description = "用户ID") Long id) {
        return blogService.queryBlogByUserId(id, current);
    }

    @Operation(summary = "查询关注Feed流")
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") @Parameter(description = "上次滚动最小时间戳") Long max,
            @RequestParam(value = "offset", defaultValue = "0") @Parameter(description = "同时间戳偏移") Integer offset) {
        return blogService.queryBlogOfFollow(max, offset);
    }

    @Operation(summary = "按店铺查询笔记")
    @GetMapping("/of/shop/{shopId}")
    public Result queryBlogByShopId(@PathVariable("shopId") @Parameter(description = "店铺ID") Long shopId,
                                    @RequestParam(value = "current", defaultValue = "1") @Parameter(description = "页码") Integer current) {
        return blogService.queryBlogByShopId(shopId, current);
    }

    @Operation(summary = "博客分页查询")
    @GetMapping("/page")
    public Result queryBlogPage(@RequestParam(value = "current", defaultValue = "1") @Parameter(description = "页码") Integer current,
                                  @RequestParam(value = "size", defaultValue = "100") @Parameter(description = "每页数量") Integer size) {
        return blogService.queryBlogPage(current, size);
    }
}