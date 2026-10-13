package com.hmdp.voucher.payment.domain;

/** 支付渠道的「服务商」维度，决定落库到 {@code tb_voucher_order.pay_type} 的值。与「渠道」分开是因为同一服务商下有多条
 * 渠道（微信有 Native 与 H5），而 {@code pay_type} 只区分到服务商粒度；支付方式应由「实际收到哪个渠道的支付结果」决定
 * （回调端点是 alipay 记 2、wechat 记 3），而非由下单时选的渠道反推 —— 下单走 mock 替身时真机验收也不会把 pay_type 记错。 */
public enum PayProvider {

    ALIPAY(2, "支付宝"),
    WECHAT(3, "微信");

    private final int orderPayType;
    private final String desc;

    PayProvider(int orderPayType, String desc) {
        this.orderPayType = orderPayType;
        this.desc = desc;
    }

    /** 落 tb_voucher_order.pay_type 的值：2 支付宝 / 3 微信（1 是余额支付，本仓库未实现，枚举值保留不删）。 */
    public int getOrderPayType() {
        return orderPayType;
    }

    public String getDesc() {
        return desc;
    }
}
