package com.hmdp.voucher.payment.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/** 主动查单结果（回调丢失时的兜底通道）。{@code amount} 一定要带回来：查单结果与支付单金额不一致时必须拒绝推进
 * （渠道侧金额被改 = 资损，宁可人工介入也不能自动置为已支付）；未成功时不要求渠道返回金额，允许为 {@code null}。 */
@Getter
@ToString
@AllArgsConstructor
public class PayQueryResult {

    /** 查单得到的状态，只会是 {@link PaymentStatus#PENDING} / {@link PaymentStatus#SUCCESS} / {@link PaymentStatus#FAILED}
     * 三者之一；{@link PaymentStatus#CLOSED} 只可能来自本系统的超时关单，渠道不会返回它。 */
    private final PaymentStatus status;

    /** 渠道交易号（支付宝 trade_no / 微信 transaction_id），未成功时为 null */
    private final String tradeNo;

    /** 渠道侧金额，单位：分；渠道未返回时为 null */
    private final Long amount;

    /** 渠道返回的状态描述（原文），仅用于日志与排障 */
    private final String rawStatus;

    public static PayQueryResult pending(String rawStatus) {
        return new PayQueryResult(PaymentStatus.PENDING, null, null, rawStatus);
    }

    public static PayQueryResult success(String tradeNo, Long amount, String rawStatus) {
        return new PayQueryResult(PaymentStatus.SUCCESS, tradeNo, amount, rawStatus);
    }

    public static PayQueryResult failed(String tradeNo, Long amount, String rawStatus) {
        return new PayQueryResult(PaymentStatus.FAILED, tradeNo, amount, rawStatus);
    }
}
