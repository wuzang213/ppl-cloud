package com.hmdp.voucher.controller;

import com.hmdp.common.domain.Result;
import com.hmdp.voucher.domain.Voucher;
import com.hmdp.voucher.domain.VoucherDTO;
import com.hmdp.voucher.service.IVoucherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Tag(name = "优惠券服务", description = "优惠券新增与查询")
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    @Operation(summary = "新增普通优惠券")
    @PostMapping
    public Result addVoucher(@RequestBody VoucherDTO dto) {
        return voucherService.addVoucher(dto);
    }

    @Operation(summary = "新增秒杀优惠券")
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody VoucherDTO dto) {
        voucherService.addSeckillVoucher(dto);
        return Result.ok(dto.getId());
    }

    @Operation(summary = "查询店铺优惠券列表")
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") @Parameter(description = "店铺ID") Long shopId) {
        return voucherService.queryVoucherOfShop(shopId);
    }
}