package com.hmdp.shop.controller;

import com.hmdp.common.annotation.LoginRequired;
import com.hmdp.common.domain.Result;
import com.hmdp.shop.domain.BookingCancelDTO;
import com.hmdp.shop.domain.BookingDTO;
import com.hmdp.shop.service.IBookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 预约服务（`specs/0012` §13.1 J3 的契约落地）。
 *
 * <p>🔴 **路径必须是 `/shop/booking/**`**：网关只按服务前缀转发，shop 路由只有
 * `/shop/**` 与 `/shop-type/**`（Nacos `gateway-routes.json`）。写成 `/booking/**` 就需要
 * 在 Nacos **新增一条路由**，而"Nacos 里的任何配置"是本项目明示的越界项。
 * 同服务先例：{@code ShopFavoriteController} 的用户维度资源就是 `/shop/favorite/**`。
 *
 * <p>🔴 **三个接口全部 `@LoginRequired`**，且路径**不在**网关 `excludePaths` 里 ——
 * 预约是写操作，一旦免登录就等于任何人都能替别人预约。
 */
@Tag(name = "预约服务", description = "提交预约、我的预约、取消预约")
@RestController
@RequestMapping("/shop/booking")
public class BookingController {

    @Resource
    private IBookingService bookingService;

    @Operation(summary = "提交预约（幂等：同门店同时间重复提交返回既有单号）")
    @PostMapping
    @LoginRequired
    public Result create(@RequestBody BookingDTO dto) {
        return bookingService.create(dto);
    }

    @Operation(summary = "查询我的预约")
    @GetMapping("/list")
    @LoginRequired
    public Result list(
            @RequestParam(value = "current", defaultValue = "1")
            @Parameter(description = "页码") Integer current) {
        return bookingService.myBookings(current);
    }

    @Operation(summary = "取消预约（仅本人可取消，重复取消视为成功）")
    @PostMapping("/cancel")
    @LoginRequired
    public Result cancel(@RequestBody BookingCancelDTO dto) {
        return bookingService.cancel(dto == null ? null : dto.getOrderId());
    }
}
