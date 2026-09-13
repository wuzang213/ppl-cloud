package com.hmdp.blog.controller;

import com.hmdp.blog.service.IBlogFavoriteService;
import com.hmdp.common.annotation.LoginRequired;
import com.hmdp.common.domain.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Tag(name = "博客收藏服务", description = "博客收藏、收藏列表、收藏状态")
@RestController
@RequestMapping("/blog/favorite")
public class BlogFavoriteController {

    @Resource
    private IBlogFavoriteService favoriteService;

    @Operation(summary = "收藏或取消收藏博客")
    @PutMapping("/{blogId}")
    @LoginRequired
    public Result favorite(@PathVariable("blogId") @Parameter(description = "博客ID") Long blogId,
                           @RequestParam("favorite") @Parameter(description = "是否收藏") Boolean favorite) {
        return favoriteService.favorite(blogId, favorite);
    }

    @Operation(summary = "查询我的收藏博客")
    @GetMapping("/list")
    @LoginRequired
    public Result list(@RequestParam(value = "current", defaultValue = "1") @Parameter(description = "页码") Integer current) {
        return favoriteService.myFavorites(current);
    }

    @Operation(summary = "查询博客收藏状态")
    @GetMapping("/status/{blogId}")
    @LoginRequired
    public Result status(@PathVariable("blogId") @Parameter(description = "博客ID") Long blogId) {
        return favoriteService.isFavorite(blogId);
    }
}