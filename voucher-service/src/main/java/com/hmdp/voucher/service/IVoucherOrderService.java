package com.hmdp.voucher.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.common.domain.Result;
import com.hmdp.voucher.domain.VoucherOrder;



public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    /** 普通代金券下单：{@code type != 1}、{@code status=1} 的券可直接同步建未支付单（无库存、无一人一券），
     * 支付 / 核销 / 退款复用既有链路。见实现类注释。 @param voucherId 代金券 id */
    Result buyNormalVoucher(Long voucherId);

    boolean createVoucherOrder(VoucherOrder voucherOrder);

    Result queryOrderById(Long id);

    Result queryOrderStatus(Long id);

    Result queryOrderList(Integer current);

    /** 发起支付：创建（或复用）支付单并返回「怎么付」。本方法不再改订单状态 —— 订单转为已支付只由渠道回调 / 主动查单经
     * {@code com.hmdp.voucher.payment.service.IPaymentService#applyNotify} 完成（旧实现曾允许任何登录用户凭订单 id 直接改订单状态，是资损口子）。
     * @param id 订单 id @param channel 渠道 code，可为空（空则用 {@code hmdp.pay.default-channel}）@param clientIp 用户端 IP，微信 H5 必填（其它渠道忽略） */
    Result payOrder(Long id, String channel, String clientIp);

    /** 查询某订单的支付单（含归属校验）；订单从未发起支付时 data 为 {@code null} */
    Result queryPayment(Long id);

    Result useOrder(Long id);

    Result refundOrder(Long id);

    Result merchantOrders(Long shopId, Integer current);

    void closeTimeoutOrder(Long orderId);
}
