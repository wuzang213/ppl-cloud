package com.hmdp.voucher.payment;

import com.hmdp.common.exception.BizIllegalException;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.channel.wechat.WechatH5Channel;
import com.hmdp.voucher.payment.channel.wechat.WechatNativeChannel;
import com.hmdp.voucher.payment.channel.wechat.WechatPayClientHolder;
import com.hmdp.voucher.payment.channel.wechat.WechatPrepay;
import com.hmdp.voucher.payment.config.PayProperties;
import com.wechat.pay.java.service.payments.h5.model.PrepayRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信预下单「请求拼装」单测（<b>纯函数，零网络</b>）。
 *
 * <p>微信的 {@code prepay} 必然发 HTTP，发单本身没法离线验证。但<b>发单请求拼错了才会失败得最惨</b>：
 * 金额口径搞错（把分当元或反过来）、必需字段漏带（appid / notify_url / payer_client_ip），
 * 都是「调渠道被拒」或「按错金额收款」。这里把这些纯组合逻辑钉死。
 *
 * <p>与支付宝的对照点：{@link AlipayAmount} 要求「分 → 元」换算，而微信 {@code amount.total}
 * <b>就是分</b>。本测试第一项断言就是防这条被照着支付宝抄成「又乘了一次 100」。
 */
class WechatPrepayRequestTest {

    private static final String APP_ID = "wx8888888888888888";
    private static final String MCH_ID = "1900000001";
    private static final String NOTIFY_URL = "https://example.com/voucher-order/pay/notify/wechat";

    // ==================== Native ====================

    @Test
    @DisplayName("Native：金额直接就是「分」，不做任何换算")
    void nativeRequestKeepsAmountInFen() {
        com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest request =
                WechatNativeChannel.buildPrepayRequest(APP_ID, MCH_ID, NOTIFY_URL,
                        "PAY1001", 20002L, 1000L, 5);

        assertEquals(1000, request.getAmount().getTotal(),
                "微信 amount.total 本来就是分；若这里变成 100000，说明照抄了支付宝的元换算");
        assertEquals("CNY", request.getAmount().getCurrency());
    }

    @Test
    @DisplayName("Native：必需字段全部带齐，超时是合法的 RFC3339")
    void nativeRequestCarriesRequiredFields() {
        com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest request =
                WechatNativeChannel.buildPrepayRequest(APP_ID, MCH_ID, NOTIFY_URL,
                        "PAY1001", 20002L, 1000L, 5);

        assertEquals(APP_ID, request.getAppid());
        assertEquals(MCH_ID, request.getMchid());
        assertEquals("PAY1001", request.getOutTradeNo());
        assertEquals(NOTIFY_URL, request.getNotifyUrl());
        assertTrue(request.getDescription().contains("20002"), request.getDescription());
        assertNotNull(request.getTimeExpire());
        assertRfc3339Seconds(request.getTimeExpire());
    }

    // ==================== H5 ====================

    @Test
    @DisplayName("H5：payer_client_ip 必须真的写进 scene_info（缺了微信直接拒单）")
    void h5RequestCarriesPayerClientIp() {
        PrepayRequest request = WechatH5Channel.buildPrepayRequest(APP_ID, MCH_ID, NOTIFY_URL,
                "PAY1001", 20002L, 1000L, 5, "203.0.113.7");

        assertNotNull(request.getSceneInfo(), "H5 支付没有 scene_info 必然被拒");
        assertEquals("203.0.113.7", request.getSceneInfo().getPayerClientIp());
        assertNotNull(request.getSceneInfo().getH5Info(), "h5_info 是 H5 支付的必填项");
        assertEquals("Wap", request.getSceneInfo().getH5Info().getType());
        assertEquals(1000, request.getAmount().getTotal(), "H5 的金额口径与 Native 一致：分");
    }

    @Test
    @DisplayName("H5：没有客户端 IP 时必须直接报错，而不是发一个注定被拒的请求")
    void h5PrepayWithoutClientIpFails() {
        PayProperties properties = new PayProperties(); // 凭据留空，反正到不了网络那一步
        WechatH5Channel channel = new WechatH5Channel(properties, new WechatPayClientHolder(properties));
        Payment payment = new Payment().setOutTradeNo("PAY1001").setAmount(1000L).setOrderId(20002L);

        assertThrows(BizIllegalException.class, () -> channel.prepay(payment, null));
        assertThrows(BizIllegalException.class, () -> channel.prepay(payment, "  "));
    }

    // ==================== 金额边界 ====================

    @Test
    @DisplayName("金额边界：0 分允许；负数与超出 int 上限必须拒绝（截断会按错金额扣款）")
    void amountBoundary() {
        assertEquals(0, WechatPrepay.toTotal(0L), "0 元券是允许的");
        assertEquals(Integer.MAX_VALUE, WechatPrepay.toTotal(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> WechatPrepay.toTotal(-1L));
        assertThrows(IllegalArgumentException.class,
                () -> WechatPrepay.toTotal((long) Integer.MAX_VALUE + 1));
    }

    // ==================== 就绪判定 ====================

    @Test
    @DisplayName("凭据不齐 → isReady=false（用于把「未配置」与「渠道不存在」区分开）")
    void channelsAreNotReadyWithoutCredentials() {
        PayProperties empty = new PayProperties();
        WechatPayClientHolder holder = new WechatPayClientHolder(empty);

        assertFalse(new WechatNativeChannel(empty, holder).isReady());
        assertFalse(new WechatH5Channel(empty, holder).isReady());
    }

    @Test
    @DisplayName("渠道 code 与枚举一致（注册表据此装配）")
    void channelCodesMatchEnum() {
        PayProperties empty = new PayProperties();
        WechatPayClientHolder holder = new WechatPayClientHolder(empty);

        assertEquals("wechat_native", new WechatNativeChannel(empty, holder).code());
        assertEquals("wechat_h5", new WechatH5Channel(empty, holder).code());
    }

    /**
     * {@code yyyy-MM-ddTHH:mm:ss±HH:mm} —— <b>不能带毫秒</b>，渠道对 {@code time_expire} 的格式比
     * RFC3339 本身更严。
     */
    private static void assertRfc3339Seconds(String value) {
        assertTrue(value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}"),
                "time_expire 必须是秒级 RFC3339（带时区偏移、无毫秒），实际=" + value);
    }
}
