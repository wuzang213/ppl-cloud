package com.hmdp.voucher.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.common.domain.Result;
import com.hmdp.voucher.domain.Voucher;
import com.hmdp.voucher.domain.VoucherDTO;

public interface IVoucherService extends IService<Voucher> {

    Result addVoucher(VoucherDTO dto);

    Result queryVoucherOfShop(Long shopId);

    void addSeckillVoucher(VoucherDTO dto);
}
