package com.hmdp.voucher.payment.domain;

/** 一次「渠道支付结果通知」被处理后的结局。显式建模（而非让 {@code applyNotify} 返回 boolean 或内部吞掉）是为了让
 * 应答语义可被测试钉死：只有 {@link #APPLIED} 与 {@link #IDEMPOTENT} 才允许对外返回「成功」，异常路径错回成功会让渠道停止重推 → 静默丢单。 */
public enum NotifyOutcome {

    /** 首次推进成功：支付单与订单都已更新，outbox 已写。 */
    APPLIED,

    /** 重复回放：状态早已是成功，本次不动任何数据（幂等）。对外仍应答成功，让渠道别再重推。 */
    IDEMPOTENT,

    /** 商户单号反查不到支付单（例如旧交易在轮换后回调）。属需人工介入的资损风险。 */
    PAYMENT_NOT_FOUND,

    /** 订单已不在「未支付」态（已被超时关单 / 已支付）却收到成功通知。这是「用户付了钱但订单不需要这笔钱」的资损场景：
     * 拒绝推进 + 告警 + 留存原文 + 人工介入。 */
    ORDER_NOT_PAYABLE,

    /** 支付单已处于 CLOSED / FAILED 终态却收到成功通知：同上，拒绝推进并留档。 */
    PAYMENT_NOT_PENDING,

    /** 金额与支付单不一致：渠道侧金额被改，拒绝推进（宁可人工介入也不能按错金额确认）。 */
    AMOUNT_MISMATCH,

    /** 渠道通知表示「未支付成功」（如 WAIT_BUYER_PAY）：不推进，也不需要告警。 */
    NOT_SUCCESS;

    /** 是否应该对渠道应答「成功」（false 会让渠道重推，从而让异常被发现）。 */
    public boolean isAckSuccess() {
        return this == APPLIED || this == IDEMPOTENT || this == NOT_SUCCESS;
    }

    /** 是否需要人工介入的告警（资损风险）。 */
    public boolean isAlarm() {
        return this == PAYMENT_NOT_FOUND || this == ORDER_NOT_PAYABLE
                || this == PAYMENT_NOT_PENDING || this == AMOUNT_MISMATCH;
    }
}
