package com.hmdp.voucher.payment.support;

import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.PayChannel;
import com.hmdp.voucher.payment.domain.PayQueryResult;
import com.hmdp.voucher.payment.domain.PrepayResult;

/**
 * 记录调用的假渠道：不签名、不发网络，只回放固定支付串并统计被问了几次。
 *
 * <p>「重复 prepay 不重复打扰渠道」这条断言就靠 {@link #prepayCount} 来钉 ——
 * 如果哪天有人把复用逻辑改坏了，次数会从 1 变成 2，测试立刻发现。
 */
public class RecordingChannel implements PayChannel {

    private final String code;
    private String prepayPayload = "MOCK-PAY-URL";
    private PayQueryResult queryResult = PayQueryResult.pending("NOTPAY");
    private boolean ready = true;

    public int prepayCount;
    public int queryCount;

    public RecordingChannel(String code) {
        this.code = code;
    }

    public RecordingChannel withPrepayPayload(String payload) {
        this.prepayPayload = payload;
        return this;
    }

    public RecordingChannel withQueryResult(PayQueryResult result) {
        this.queryResult = result;
        return this;
    }

    public RecordingChannel withReady(boolean ready) {
        this.ready = ready;
        return this;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    /** 最后一次 prepay 收到的客户端 IP —— 用于钉「H5 的 payer_client_ip 有没有真的传下来」 */
    public String lastClientIp;

    @Override
    public PrepayResult prepay(Payment payment, String clientIp) {
        prepayCount++;
        this.lastClientIp = clientIp;
        return PrepayResult.forChannel(code, prepayPayload);
    }

    @Override
    public PayQueryResult query(Payment payment) {
        queryCount++;
        return queryResult;
    }
}
