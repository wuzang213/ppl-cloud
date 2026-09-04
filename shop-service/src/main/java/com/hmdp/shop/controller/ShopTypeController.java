package com.hmdp.shop.controller;

import com.hmdp.common.domain.Result;
import com.hmdp.shop.service.IShopTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Tag(name = "店铺类型服务", description = "店铺类型列表")
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @Operation(summary = "查询店铺类型列表")
    @GetMapping("list")
    public Result queryTypeList() {
        return typeService.queryTypeList();
    }
}