package com.hmdp.voucher.payment;

/** 支付宝交易状态语义（唯一判定入口，渠道查单与回调解析共用）。官方串：{@code WAIT_BUYER_PAY}（待付款）、
 * {@code TRADE_CLOSED}（未付款超时关闭 / 付款后退款）、{@code TRADE_SUCCESS}（支付成功）、{@code TRADE_FINISHED}
 * （完结，不可退款）。抽成一处是因为查单与回调必须对「什么算已支付」给出同一个答案，否则会出现「有的订单能推进、有的不能」。 */
public final class AlipayTradeStatus {

    public static final String WAIT_BUYER_PAY = "WAIT_BUYER_PAY";
    public static final String TRADE_CLOSED = "TRADE_CLOSED";
    public static final String TRADE_SUCCESS = "TRADE_SUCCESS";
    public static final String TRADE_FINISHED = "TRADE_FINISHED";

    private AlipayTradeStatus() {
    }

    /** 是否表示「钱已经收了」。{@code TRADE_FINISHED} 也算：它意味着交易完结、不可退款。只认 {@code TRADE_SUCCESS}
     * 会把走到完结态的交易判成未支付，进而在订单超时后被关单 —— 明确的资损。 */
    public static boolean isPaid(String tradeStatus) {
        return TRADE_SUCCESS.equals(tradeStatus) || TRADE_FINISHED.equals(tradeStatus);
    }

    /** 是否表示交易被关闭（未付款超时 / 已退款） */
    public static boolean isClosed(String tradeStatus) {
        return TRADE_CLOSED.equals(tradeStatus);
    }
}
