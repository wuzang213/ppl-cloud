package com.hmdp.voucher.payment.dto;

import lombok.Data;

/** 「发起支付」请求体。本类禁止出现任何金额字段（{@code amount} / {@code payValue} / {@code price} / {@code total}…）：
 * 金额只能由服务端从 {@code tb_voucher} 反查计算（{@link com.hmdp.voucher.payment.PaymentPricing}），前端传的一律无效。
 * 多了金额字段就等于给「1 分钱买 100 元券」开口子（是资损漏洞，不是风格问题），{@code PayOrderRequestTest} 用反射守住这条约束。 */
@Data
public class PayOrderRequest {

    /** 支付渠道 code：{@code alipay_page} / {@code wechat_native} / {@code wechat_h5} / {@code mock}。
     * 留空则用 {@code hmdp.pay.default-channel}。 */
    private String channel;
}
