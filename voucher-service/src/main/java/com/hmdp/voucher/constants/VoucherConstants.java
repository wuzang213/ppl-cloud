package com.hmdp.voucher.constants;

public final class VoucherConstants {

    public static final String ORDER_NOT_FOUND = "订单不存在";
    public static final String ORDER_ACCESS_DENIED = "无权查看订单";
    public static final String ORDER_STATUS_INVALID = "订单状态异常";
    public static final String PERMISSION_DENIED = "无权限";
    public static final String STOCK_NOT_ENOUGH = "库存不足";
    public static final String DUPLICATE_ORDER = "不能重复下单";
    public static final String ORDER_CREATE_FAILED = "订单创建失败";

    public static final int ORDER_TIMEOUT_DELAY_MS = 15 * 60 * 1000;

    private VoucherConstants() {
    }
}
