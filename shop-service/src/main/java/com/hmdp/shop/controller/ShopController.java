package com.hmdp.shop.controller;

import com.hmdp.common.annotation.LoginRequired;
import com.hmdp.common.domain.Result;
import com.hmdp.shop.domain.Shop;
import com.hmdp.shop.domain.ShopDTO;
import com.hmdp.shop.service.IShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Tag(name = "店铺服务", description = "店铺查询、新增、修改")
@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    private IShopService shopService;

    @Operation(summary = "根据id查询店铺")
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") @Parameter(description = "店铺ID") Long id) {
        return shopService.queryById(id);
    }

    @Operation(summary = "新增店铺")
    @PostMapping
    public Result saveShop(@RequestBody ShopDTO dto) {
        return shopService.saveShop(dto);
    }

    @Operation(summary = "修改店铺")
    @PutMapping
    public Result updateShop(@RequestBody ShopDTO dto) {
        return shopService.update(dto);
    }

    @Operation(summary = "删除店铺")
    @DeleteMapping("/{id}")
    @LoginRequired
    public Result deleteShop(@PathVariable("id") @Parameter(description = "店铺ID") Long id) {
        return shopService.deleteShop(id);
    }

    @Operation(summary = "按类型和坐标查询店铺")
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") @Parameter(description = "店铺类型ID") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") @Parameter(description = "页码") Integer current,
            @RequestParam(value = "x", required = false) @Parameter(description = "经度") Double x,
            @RequestParam(value = "y", required = false) @Parameter(description = "纬度") Double y,
            @RequestParam(value = "distance", required = false) @Parameter(description = "距离") Integer distance) {
        return shopService.queryShopByType(typeId, current, x, y, distance);
    }

    /** 按名称搜索店铺：请求不再直接访问该接口，留给 agent 精确查找店铺。 */
    @Operation(summary = "按名称搜索店铺")
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) @Parameter(description = "店铺名称关键字") String name,
            @RequestParam(value = "current", defaultValue = "1") @Parameter(description = "页码") Integer current) {
        return shopService.queryShopByName(name, current);
    }

    /* 搜索索引反查内部口子（非公网）：网关 AuthGlobalFilter 按 hmdp.auth.internalPaths 主动 403（黑名单优先于白名单）。
     * 不用现成的 /shop/{id}：它同样有 viewCounter.asyncIncr 副作用；反查也不能走布隆过滤器（新店可能尚未写入布隆
     * → 被误判「已删除」→ 误删索引）。 */

    @Operation(summary = "【内部】反查店铺搜索文档（不计数、含分类名）")
    @GetMapping("/search-doc/{id:\\d+}")
    public Result searchDoc(@PathVariable("id") @Parameter(description = "店铺ID") Long id) {
        return shopService.queryShopSearchDoc(id);
    }

    @Operation(summary = "【内部】分页反查店铺搜索文档（全量索引用）")
    @GetMapping("/search-doc/page")
    public Result searchDocPage(
            @RequestParam(value = "current", defaultValue = "1") @Parameter(description = "页码") Integer current,
            @RequestParam(value = "size", defaultValue = "200") @Parameter(description = "每页数量") Integer size) {
        return shopService.queryShopSearchDocPage(current, size);
    }
}
