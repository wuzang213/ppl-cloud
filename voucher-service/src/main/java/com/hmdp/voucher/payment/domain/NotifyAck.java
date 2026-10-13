package com.hmdp.voucher.payment.domain;

/** 一次渠道通知经「解析 + {@code com.hmdp.voucher.payment.service.IPaymentService#applyNotify}」后的中性应答决策。与具体渠道的线格式解耦：
 * 支付宝的 {@code success/failure} 字符串、微信的 HTTP 200/4xx/5xx + JSON 都由 Controller 再翻译成各自协议。
 * <p>三个语义维度：
 * <ul>
 *   <li>{@link #isAckSuccess()}：是否应告诉渠道「已收到、别再重推」。异常/需人工的结局为 false，
 *       让渠道继续重推（否则静默丢单）。直接包裹 {@link NotifyOutcome#isAckSuccess()}。</li>
 *   <li>{@link #isRejected()}：协议层是否被拒（报文格式/验签不过）。对应微信 400 BAD_REQUEST；
 *       非 ack 且非 rejected 才是 5xx（已落地但需人工，渠道应重推）。</li>
 *   <li>{@link #getMessage()}：给渠道/日志看的结局说明（微信 FAIL 的 message、支付宝仅日志用）。</li>
 * </ul> */
public class NotifyAck {

    private final boolean ackSuccess;
    private final boolean rejected;
    private final String message;

    private NotifyAck(boolean ackSuccess, boolean rejected, String message) {
        this.ackSuccess = ackSuccess;
        this.rejected = rejected;
        this.message = message;
    }

    /** 由业务结局派生：ack 语义与 {@link NotifyOutcome} 完全一致，rejected 固定为 false。 */
    public static NotifyAck fromOutcome(NotifyOutcome outcome) {
        return new NotifyAck(outcome.isAckSuccess(), false, outcome.name());
    }

    /** 协议层拒绝（报文/验签不过）：不触碰任何状态、不 ack、不告警、对应微信 400。 */
    public static NotifyAck reject(String reason) {
        return new NotifyAck(false, true, reason);
    }

    public boolean isAckSuccess() {
        return ackSuccess;
    }

    public boolean isRejected() {
        return rejected;
    }

    public String getMessage() {
        return message;
    }
}
