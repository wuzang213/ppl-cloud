package com.hmdp.voucher.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.common.domain.Result;
import com.hmdp.voucher.domain.VoucherOrder;



public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    boolean createVoucherOrder(VoucherOrder voucherOrder);

    Result queryOrderById(Long id);

    Result queryOrderStatus(Long id);

    Result queryOrderList(Integer current);

    Result payOrder(Long id);

    Result useOrder(Long id);

    Result refundOrder(Long id);

    Result merchantOrders(Long shopId, Integer current);

    void closeTimeoutOrder(Long orderId);
}
