package com.hmdp.voucher.payment;

import com.hmdp.voucher.payment.config.PayProperties;
import com.hmdp.voucher.payment.domain.PayProvider;
import com.hmdp.voucher.payment.mock.MockNotifySigner;
import com.hmdp.voucher.payment.notify.AlipayNotifyParser;
import com.hmdp.voucher.payment.notify.WechatNotifyParser;
import com.hmdp.voucher.payment.support.AlipayNotificationFixture;
import com.hmdp.voucher.payment.support.WechatNotificationFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mock 渠道造的报文<b>能过真实解析器</b>（零网络）。
 *
 * <p>这是 mock 渠道唯一的「有效性证明」：如果造出来的报文连我们自己的验签都过不了，
 * 那 T11 验收里的整个闭环就是自欺欺人。所以这里刻意用<b>真实的</b>
 * {@link AlipayNotifyParser} / {@link WechatNotifyParser} 去接，
 * 而不是断言「字段看起来对」。
 *
 * <p>注意夹具与生产代码是<b>两套独立实现</b>（{@code support/} 下的 Fixture 不复用
 * {@link MockNotifySigner}）：如果复用，就成了同一份代码自证，证明力归零。
 */
class MockNotifySignerTest {

    private static AlipayNotificationFixture alipayFixture;
    private static WechatNotificationFixture wechatFixture;

    private static final String OUT_TRADE_NO = "PAY1001";
    private static final long AMOUNT_FEN = 1000L;

    @BeforeAll
    static void initFixtures() throws Exception {
        alipayFixture = new AlipayNotificationFixture();
        wechatFixture = new WechatNotificationFixture();
    }

    // ==================================================================
    // 配置装配（模拟 T10 要写进 Nacos 的那套取值）
    // ==================================================================

    /**
     * 支付宝侧：把夹具密钥对当作「mock 的签名私钥 + 支付宝公钥」。
     *
     * <p>这正是 {@code PayMockGuard} 在启动时会校验的配对关系 ——
     * 这里能配上，说明 guard 的校验口径与实际加签/验签口径一致。
     */
    private static PayProperties alipayProperties() {
        PayProperties p = alipayFixture.properties();
        p.getMock().setSignPrivateKey(alipayFixture.merchantPrivateKey);
        return p;
    }

    private static PayProperties wechatProperties() {
        PayProperties p = new PayProperties();
        PayProperties.Wechat w = p.getWechat();
        w.setAppId(WechatNotificationFixture.APP_ID);
        w.setMchId(WechatNotificationFixture.MCH_ID);
        w.setApiV3Key(WechatNotificationFixture.API_V3_KEY);
        w.setWechatPayPublicKeyId(WechatNotificationFixture.PUBLIC_KEY_ID);
        w.setWechatPayPublicKey(wechatFixture.publicKeyPem());
        w.setNotifyUrl(WechatNotificationFixture.NOTIFY_URL);
        p.getMock().setSignPrivateKey(wechatFixture.privateKeyBase64());
        return p;
    }

    // ==================================================================
    // 支付宝
    // ==================================================================

    @Test
    @DisplayName("mock 支付宝回调能过真实验签，且金额以「元」两位小数承载、状态映射正确")
    void mockAlipayNotifyPassesRealVerifier() {
        Map<String, String> form = MockNotifySigner.alipayNotify(
                alipayProperties(), OUT_TRADE_NO, AMOUNT_FEN, true);

        // 「分 → 元」是 mock 与真实渠道共用的口径，写错就是静默多收/少收 100 倍
        assertEquals("10.00", form.get("total_amount"));
        assertNotNull(form.get("sign"));
        assertEquals("RSA2", form.get("sign_type"));

        AlipayNotifyParser.ParseResult result = new AlipayNotifyParser(alipayProperties()).parse(form);

        assertTrue(result.accepted(), "mock 报文应通过真实验签：" + result.rejectReason());
        assertNotNull(result.command());
        assertEquals(PayProvider.ALIPAY, result.command().getProvider());
        assertEquals(OUT_TRADE_NO, result.command().getOutTradeNo());
        assertEquals(MockNotifySigner.virtualTradeNo(OUT_TRADE_NO), result.command().getTradeNo());
        assertEquals(AMOUNT_FEN, result.command().getAmount(), "金额必须回到「分」且与支付单同口径");
        assertTrue(result.command().isSuccess());
        assertEquals(AlipayTradeStatus.TRADE_SUCCESS, result.command().getRawStatus());
    }

    @Test
    @DisplayName("mock 支付宝「未支付」回调：解析通过但不表示成功（不推进、不告警）")
    void mockAlipayNotSuccessIsParsedButNotPaid() {
        Map<String, String> form = MockNotifySigner.alipayNotify(
                alipayProperties(), OUT_TRADE_NO, AMOUNT_FEN, false);

        assertEquals(AlipayTradeStatus.WAIT_BUYER_PAY, form.get("trade_status"));

        AlipayNotifyParser.ParseResult result = new AlipayNotifyParser(alipayProperties()).parse(form);

        assertTrue(result.accepted(), "未支付的通知本身是合法的：" + result.rejectReason());
        assertFalse(result.command().isSuccess(), "WAIT_BUYER_PAY 绝不能被当成已支付");
    }

    @Test
    @DisplayName("mock 支付宝回调签名被改动一位 → 真实解析器必须拒绝")
    void tamperedAlipaySignatureIsRejected() {
        Map<String, String> form = MockNotifySigner.alipayNotify(
                alipayProperties(), OUT_TRADE_NO, AMOUNT_FEN, true);
        form.put("total_amount", "9999.00"); // 金额被改，签名随即失效

        AlipayNotifyParser.ParseResult result = new AlipayNotifyParser(alipayProperties()).parse(form);

        assertFalse(result.accepted(), "改过金额的报文绝不能通过验签");
        assertEquals("sign_verify_failed", result.rejectReason());
    }

    // ==================================================================
    // 微信
    // ==================================================================

    @Test
    @DisplayName("mock 微信通知能过真实验签 + AES-GCM 解密，金额保持「分」不做任何换算")
    void mockWechatNotifyPassesRealVerifier() {
        WechatNotifyParser parser = new WechatNotifyParser(wechatProperties());
        MockNotifySigner.WechatNotify notify = MockNotifySigner.wechatNotify(
                wechatProperties(), OUT_TRADE_NO, AMOUNT_FEN, true);

        // 4 个请求头缺一不可；serial 必须等于配置的公钥 ID，否则 NotificationParser 会拒
        assertEquals(WechatNotificationFixture.PUBLIC_KEY_ID, notify.serial());
        assertNotNull(notify.nonce());
        assertNotNull(notify.timestamp());
        assertNotNull(notify.signature());
        assertTrue(notify.body().contains("AEAD_AES_256_GCM"), "resource 必须是加密的");
        assertFalse(notify.body().contains(OUT_TRADE_NO), "商户单号不能出现在明文里（应只存在于密文中）");

        WechatNotifyParser.ParseResult result = parser.parse(
                notify.serial(), notify.nonce(), notify.timestamp(), notify.signature(), notify.body());

        assertTrue(result.accepted(), "mock 微信通知应通过真实验签与解密：" + result.rejectReason());
        assertNotNull(result.command());
        assertEquals(PayProvider.WECHAT, result.command().getProvider());
        assertEquals(OUT_TRADE_NO, result.command().getOutTradeNo());
        assertEquals(AMOUNT_FEN, result.command().getAmount(),
                "微信 amount.total 本来就是分，多乘或少乘 100 倍都会在这里暴露");
        assertTrue(result.command().isSuccess());
        assertEquals(WechatTradeState.SUCCESS, result.command().getRawStatus());
    }

    @Test
    @DisplayName("mock 微信通知被改动一个字节 → 验签必须拒绝（证明签名是对原始 body 做的）")
    void tamperedWechatBodyIsRejected() {
        WechatNotifyParser parser = new WechatNotifyParser(wechatProperties());
        MockNotifySigner.WechatNotify notify = MockNotifySigner.wechatNotify(
                wechatProperties(), OUT_TRADE_NO, AMOUNT_FEN, true);

        String tampered = notify.body().replace("\"summary\":\"支付成功\"", "\"summary\":\"支付成功。\"");
        assertFalse(tampered.equals(notify.body()), "前置条件：body 确实被改动了");

        WechatNotifyParser.ParseResult result = parser.parse(
                notify.serial(), notify.nonce(), notify.timestamp(), notify.signature(), tampered);

        assertFalse(result.accepted(), "body 被改动后签名必然失效");
        assertTrue(result.rejectReason().startsWith("validation_failed")
                        || result.rejectReason().startsWith("malformed_message")
                        || result.rejectReason().startsWith("decrypt_failed"),
                "拒绝原因应落在验签/报文类：" + result.rejectReason());
    }

    @Test
    @DisplayName("mock 微信「未支付」通知：解密通过但 trade_state 不是 SUCCESS")
    void mockWechatNotSuccessIsParsedButNotPaid() {
        WechatNotifyParser parser = new WechatNotifyParser(wechatProperties());
        MockNotifySigner.WechatNotify notify = MockNotifySigner.wechatNotify(
                wechatProperties(), OUT_TRADE_NO, AMOUNT_FEN, false);

        WechatNotifyParser.ParseResult result = parser.parse(
                notify.serial(), notify.nonce(), notify.timestamp(), notify.signature(), notify.body());

        assertTrue(result.accepted(), result.rejectReason());
        assertFalse(result.command().isSuccess(), "NOTPAY 不是已支付");
        assertEquals(WechatTradeState.NOTPAY, result.command().getRawStatus());
    }

    // ==================================================================
    // 虚拟交易号
    // ==================================================================

    @Test
    @DisplayName("虚拟交易号带 MOCK 前缀：验收后查库能一眼分辨哪些是造假数据")
    void virtualTradeNoIsRecognizable() {
        String tradeNo = MockNotifySigner.virtualTradeNo(OUT_TRADE_NO);
        assertTrue(tradeNo.startsWith("MOCK"));
        assertTrue(tradeNo.endsWith(OUT_TRADE_NO));
        assertTrue(tradeNo.length() <= 64, "必须能塞进 tb_payment.trade_no varchar(64)");
    }
}
