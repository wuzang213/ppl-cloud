package com.hmdp.voucher.payment;

/** 微信支付交易状态语义（唯一判定入口，渠道查单与回调解析共用）。官方 {@code trade_state}：{@code SUCCESS}（成功）、
 * {@code REFUND}（转入退款）、{@code NOTPAY}（未支付）、{@code CLOSED}（已关闭）、{@code REVOKED}（已撤销）、
 * {@code USERPAYING}（支付中）、{@code PAYERROR}（失败）、{@code ACCEPT}（已受理）。与 {@link AlipayTradeStatus} 的差异（刻意）：支付宝把 TRADE_FINISHED 也算已收款，微信只有 SUCCESS 算；REFUND 既不算成功也不算关闭（钱曾到账后被退），当成功会没付钱拿券、当关闭又抹掉「钱动过」的事实，故落「未识别」分支 → 记 WARN + 留存原文，等订单超时关单或人工介入。 */
public final class WechatTradeState {

    public static final String SUCCESS = "SUCCESS";
    public static final String REFUND = "REFUND";
    public static final String NOTPAY = "NOTPAY";
    public static final String CLOSED = "CLOSED";
    public static final String REVOKED = "REVOKED";
    public static final String USERPAYING = "USERPAYING";
    public static final String PAYERROR = "PAYERROR";
    public static final String ACCEPT = "ACCEPT";

    private WechatTradeState() {
    }

    /** 是否表示「钱已经收了」。微信侧<b>只有 SUCCESS</b>，与支付宝的判定不对称。 */
    public static boolean isPaid(String tradeState) {
        return SUCCESS.equals(tradeState);
    }

    /** 是否表示「这笔不会再有钱进来了」（可安全不做处理）：{@code CLOSED}（超时未支付被关）、{@code REVOKED}
     * （已撤销）、{@code PAYERROR}（支付失败）三者都意味着交易已死。{@code REFUND} 不在此列 —— 见类注释。 */
    public static boolean isClosed(String tradeState) {
        return CLOSED.equals(tradeState) || REVOKED.equals(tradeState) || PAYERROR.equals(tradeState);
    }

    /** 是否属于「需要人看一眼」的异常状态：目前只有 {@code REFUND} —— 本仓库本期不做退款，出现它一定是异常来源。 */
    public static boolean isAnomaly(String tradeState) {
        return REFUND.equals(tradeState);
    }
}
