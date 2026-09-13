package com.hmdp.voucher.controller;

import com.hmdp.common.annotation.LoginRequired;
import com.hmdp.common.domain.Result;
import com.hmdp.voucher.service.IVoucherOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Tag(name = "优惠券订单服务", description = "秒杀下单、支付、核销、退款、订单查询")
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @Operation(summary = "秒杀优惠券")
    @PostMapping("seckill/{id}")
    @LoginRequired
    public Result seckillVoucher(@PathVariable("id") @Parameter(description = "优惠券ID") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    @Operation(summary = "查询我的订单列表")
    @GetMapping("/list")
    @LoginRequired
    public Result list(@RequestParam(value = "current", defaultValue = "1") @Parameter(description = "页码") Integer current) {
        return voucherOrderService.queryOrderList(current);
    }

    @Operation(summary = "查询订单详情")
    @GetMapping("/{id}")
    @LoginRequired
    public Result detail(@PathVariable("id") @Parameter(description = "订单ID") Long id) {
        return voucherOrderService.queryOrderById(id);
    }

    @Operation(summary = "查询订单状态")
    @GetMapping("/status/{id}")
    public Result status(@PathVariable("id") @Parameter(description = "订单ID") Long id) {
        return voucherOrderService.queryOrderStatus(id);
    }

    @Operation(summary = "支付订单")
    @PostMapping("/pay/{id}")
    @LoginRequired
    public Result pay(@PathVariable("id") @Parameter(description = "订单ID") Long id) {
        return voucherOrderService.payOrder(id);
    }

    @Operation(summary = "核销订单")
    @PostMapping("/use/{id}")
    @LoginRequired
    public Result use(@PathVariable("id") @Parameter(description = "订单ID") Long id) {
        return voucherOrderService.useOrder(id);
    }

    @Operation(summary = "退款订单")
    @PostMapping("/refund/{id}")
    @LoginRequired
    public Result refund(@PathVariable("id") @Parameter(description = "订单ID") Long id) {
        return voucherOrderService.refundOrder(id);
    }

    @Operation(summary = "商家订单列表")
    @GetMapping("/admin/orders")
    @LoginRequired
    public Result merchantOrders(@RequestParam @Parameter(description = "店铺ID") Long shopId,
                                 @RequestParam(value = "current", defaultValue = "1") @Parameter(description = "页码") Integer current) {
        return voucherOrderService.merchantOrders(shopId, current);
    }
}