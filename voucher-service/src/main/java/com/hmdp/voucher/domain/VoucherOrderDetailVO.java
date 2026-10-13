package com.hmdp.voucher.domain;

import com.hmdp.voucher.payment.dto.PaymentVO;
import lombok.Getter;
import lombok.Setter;

/** 订单详情视图 = {@link VoucherOrder} 全部字段 + 一个 {@code payment}。继承实体而非平铺 VO：返回值只增一个字段、
 * 不改结构与已有字段（实体加字段自动同步，注解随继承生效）；拷贝必须手写 —— 实体 {@code @Accessors(chain=true)} 的
 * setter 返回 this，{@code BeanUtils.copyProperties} 会静默拷不到（VO 全 null）。{@code Long} 经 {@code JsonConfig} 序列化为字符串（如 "1000"）。 */
@Getter
@Setter
public class VoucherOrderDetailVO extends VoucherOrder {

    /** 支付单视图；订单从未发起支付时为 {@code null} */
    private PaymentVO payment;

    public static VoucherOrderDetailVO of(VoucherOrder order, PaymentVO payment) {
        VoucherOrderDetailVO vo = new VoucherOrderDetailVO();
        if (order != null) {
            vo.setId(order.getId());
            vo.setUserId(order.getUserId());
            vo.setVoucherId(order.getVoucherId());
            vo.setPayType(order.getPayType());
            vo.setStatus(order.getStatus());
            vo.setCreateTime(order.getCreateTime());
            vo.setPayTime(order.getPayTime());
            vo.setUseTime(order.getUseTime());
            vo.setRefundTime(order.getRefundTime());
            vo.setUpdateTime(order.getUpdateTime());
        }
        vo.setPayment(payment);
        return vo;
    }
}
