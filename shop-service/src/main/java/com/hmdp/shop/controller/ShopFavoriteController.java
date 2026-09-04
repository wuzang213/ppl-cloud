package com.hmdp.shop.controller;

import com.hmdp.common.domain.Result;
import com.hmdp.shop.service.IShopFavoriteService;
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

@Tag(name = "店铺收藏服务", description = "店铺收藏、收藏列表、收藏状态")
@RestController
@RequestMapping("/shop/favorite")
public class ShopFavoriteController {

    @Resource
    private IShopFavoriteService favoriteService;

    @Operation(summary = "收藏或取消收藏店铺")
    @PutMapping("/{shopId}")
    public Result favorite(@PathVariable("shopId") @Parameter(description = "店铺ID") Long shopId,
                           @RequestParam("favorite") @Parameter(description = "是否收藏") Boolean favorite) {
        return favoriteService.favorite(shopId, favorite);
    }

    @Operation(summary = "查询我的收藏店铺")
    @GetMapping("/list")
    public Result list(@RequestParam(value = "current", defaultValue = "1") @Parameter(description = "页码") Integer current) {
        return favoriteService.myFavorites(current);
    }

    @Operation(summary = "查询店铺收藏状态")
    @GetMapping("/status/{shopId}")
    public Result status(@PathVariable("shopId") @Parameter(description = "店铺ID") Long shopId) {
        return favoriteService.isFavorite(shopId);
    }
}