package com.hmdp.voucher.payment.mock;

import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.PayChannel;
import com.hmdp.voucher.payment.config.PayProperties;
import com.hmdp.voucher.payment.domain.PayChannelType;
import com.hmdp.voucher.payment.domain.PayQueryResult;
import com.hmdp.voucher.payment.domain.PrepayResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 验收用 mock 支付渠道。它替换的只有「渠道那一跳」：{@code prepay} 不去找支付宝/微信要收银台，而是返回一个指向本服务
 * mock 收银台的地址；用户「付款」= 调 {@code POST /voucher-order/pay/mock/pay/{outTradeNo}}，由它造一份签名合法的报文
 * 投递给真实的 {@code /voucher-order/pay/notify/alipay}（或 {@code /wechat}），故「验签 → 状态机 → 订单 → outbox」全是真的。默认必须关闭：它能凭一个 HTTP 请求把任意订单置为已支付，故 {@code hmdp.pay.mock.enabled} 默认 false，生产 profile 下开启会导致启动失败（见 {@code PayMockGuard}）。{@code query} 永远返回「未支付」—— mock 背后没有远端可查，返回「成功」会掩盖「回调链路没通」这个最该被发现的故障。落库的 {@code tb_payment.channel="mock"} 与投递回调的 {@code pay_type=2/3} 不一致是刻意的：同一套状态机用两种渠道报文各验一遍。 */
@Component
@ConditionalOnProperty(prefix = "hmdp.pay.mock", name = "enabled", havingValue = "true")
@Slf4j
public class MockPayChannel implements PayChannel {

    /** mock 没有远端可查，这个串只会出现在日志里，用来提醒「该状态不来自渠道」 */
    static final String RAW_STATUS_NO_REMOTE = "MOCK_NO_REMOTE_QUERY";

    private final PayProperties payProperties;
    private final int serverPort;

    public MockPayChannel(PayProperties payProperties, @Value("${server.port:8084}") int serverPort) {
        this.payProperties = payProperties;
        this.serverPort = serverPort;
    }

    @Override
    public String code() {
        return PayChannelType.MOCK.getCode();
    }

    /** 能否承担 mock 渠道的职责。判据是「有没有签名私钥」而不是「能不能拼出一个 URL」：拼 URL 不需要任何凭据，但那样得到
     * 的收银台永远付不了款（投递回调时才会报错），用户会以为「支付功能坏了」；缺私钥时直接判为「渠道未配置」更诚实。 */
    @Override
    public boolean isReady() {
        PayProperties.Mock mock = payProperties.getMock();
        return mock.isEnabled() && mock.getSignPrivateKey() != null && !mock.getSignPrivateKey().isBlank();
    }

    @Override
    public PrepayResult prepay(Payment payment, String clientIp) {
        // clientIp 用不到：mock 不涉及渠道侧风控
        String baseUrl = MockPayUrls.baseUrl(payProperties, serverPort);
        String cashier = MockPayUrls.cashier(baseUrl, payment.getOutTradeNo());
        log.warn("mock 渠道发起支付（非生产渠道）：outTradeNo={}, 收银台={}", payment.getOutTradeNo(), cashier);
        return PrepayResult.url(code(), cashier);
    }

    @Override
    public PayQueryResult query(Payment payment) {
        // 见类注释：绝不返回「成功」，否则会掩盖「回调链路没通」这个最该被发现的故障
        log.debug("mock 渠道无远端可查，保持待支付：outTradeNo={}", payment.getOutTradeNo());
        return PayQueryResult.pending(RAW_STATUS_NO_REMOTE);
    }
}
