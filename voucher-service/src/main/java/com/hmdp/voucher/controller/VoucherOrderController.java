package com.hmdp.voucher.controller;

import com.hmdp.common.annotation.LoginRequired;
import com.hmdp.common.domain.Result;
import com.hmdp.voucher.payment.dto.PayOrderRequest;
import com.hmdp.voucher.payment.web.PaymentClientIpResolver;
import com.hmdp.voucher.service.IVoucherOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

@Tag(name = "代金券订单服务", description = "普通/秒杀下单、支付、核销、退款、订单查询")
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private PaymentClientIpResolver clientIpResolver;

    @Operation(summary = "秒杀代金券")
    @PostMapping("seckill/{id}")
    @LoginRequired
    public Result seckillVoucher(@PathVariable("id") @Parameter(description = "代金券ID") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /** 购买普通代金券（下单）：无库存、无一人一券；产出未支付订单，随后走 {@code POST /pay/{id}} 支付。 */
    @Operation(summary = "购买普通代金券（下单）")
    @PostMapping("/buy/{id}")
    @LoginRequired
    public Result buy(@PathVariable("id") @Parameter(description = "代金券ID") Long voucherId) {
        return voucherOrderService.buyNormalVoucher(voucherId);
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

    @LoginRequired
    @Operation(summary = "查询订单状态")
    @GetMapping("/status/{id}")
    public Result status(@PathVariable("id") @Parameter(description = "订单ID") Long id) {
        return voucherOrderService.queryOrderStatus(id);
    }

    /** 发起支付：创建（或复用）支付单并返回渠道支付串。请求体 {@code {"channel":"..."}} 可省略（缺省
     * {@code hmdp.pay.default-channel}），无金额字段（金额只由服务端按券售价算）；本接口不改订单状态，
     * 订单转为已支付只发生在渠道回调 / 主动查单之后。 */
    @Operation(summary = "发起支付（返回渠道支付串）")
    @PostMapping("/pay/{id}")
    @LoginRequired
    public Result pay(@PathVariable("id") @Parameter(description = "订单ID") Long id,
                      @RequestBody(required = false) PayOrderRequest request,
                      HttpServletRequest httpRequest) {
        return voucherOrderService.payOrder(id,
                request == null ? null : request.getChannel(),
                clientIpResolver.resolve(httpRequest));
    }

    @Operation(summary = "查询订单的支付单")
    @GetMapping("/pay/{id}")
    @LoginRequired
    public Result payment(@PathVariable("id") @Parameter(description = "订单ID") Long id) {
        return voucherOrderService.queryPayment(id);
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