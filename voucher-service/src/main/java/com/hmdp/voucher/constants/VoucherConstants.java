package com.hmdp.voucher.constants;

public final class VoucherConstants {

    public static final String ORDER_NOT_FOUND = "订单不存在";
    public static final String ORDER_ACCESS_DENIED = "无权查看订单";
    public static final String ORDER_STATUS_INVALID = "订单状态异常";
    public static final String PERMISSION_DENIED = "无权限";
    public static final String STOCK_NOT_ENOUGH = "库存不足";
    public static final String DUPLICATE_ORDER = "不能重复下单";
    public static final String ORDER_CREATE_FAILED = "订单创建失败";

    // ==================== 支付相关 ====================
    /** 代金券不存在（计价时反查不到 tb_voucher） */
    public static final String VOUCHER_NOT_FOUND = "代金券不存在";
    /** 代金券金额未配置 / 为负，无法计价 */
    public static final String VOUCHER_AMOUNT_INVALID = "代金券金额异常，无法发起支付";
    /** 秒杀代金券不能走"普通券下单"入口（type=1），必须走秒杀入口 */
    public static final String SECKILL_VOUCHER_WRONG_PATH = "秒杀代金券请走秒杀入口下单";
    /** 不支持的支付渠道（请求体里的 channel 不在渠道注册表里） */
    public static final String PAY_CHANNEL_NOT_SUPPORTED = "不支持的支付渠道";
    /** 渠道已注册但凭据未配置（Nacos 里 hmdp.pay.* 没填） */
    public static final String PAY_CHANNEL_NOT_CONFIGURED = "支付渠道未配置，无法发起支付";
    /** 支付单不存在（订单存在但未发起过支付 = 正常情况，不是错误） */
    public static final String PAYMENT_NOT_FOUND = "支付单不存在";
    /** 支付单归属校验失败 */
    public static final String PAYMENT_ACCESS_DENIED = "无权查看该支付单";
    /** 订单已关闭 / 已支付后仍收到成功回调（资损场景，需人工介入） */
    public static final String PAY_NOTIFY_REJECTED_ORDER_CLOSED = "订单已关闭，拒绝推进支付状态";
    /** 渠道未返回可用的支付串 */
    public static final String PREPAY_FAILED = "发起支付失败，请稍后重试";
    /** 该订单已完成支付，不能再次发起 */
    public static final String PAYMENT_ALREADY_SUCCESS = "该订单已完成支付";
    /** 支付单已处于终止态（关闭/失败），需重新下单 */
    public static final String PAYMENT_TERMINAL = "该订单的支付单已终止，请重新下单";
    /** 已有进行中的支付单，不允许换渠道（一单一支付单由 uk_order_id 保证） */
    public static final String PAYMENT_CHANNEL_LOCKED = "该订单已有进行中的支付单，不能切换支付渠道";

    public static final int ORDER_TIMEOUT_DELAY_MS = 15 * 60 * 1000;

    private VoucherConstants() {
    }
}
