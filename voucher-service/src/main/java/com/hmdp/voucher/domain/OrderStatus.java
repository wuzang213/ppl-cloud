package com.hmdp.voucher.domain;

import lombok.Getter;

@Getter
public enum OrderStatus {
    UNPAID(1, "未支付"),
    PAID(2, "已支付"),
    USED(3, "已核销"),
    CANCELED(4, "已取消"),
    REFUNDING(5, "退款中"),
    REFUNDED(6, "已退款");

    private final int value;
    private final String desc;

    OrderStatus(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }
}
