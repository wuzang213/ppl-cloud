package com.hmdp.voucher.payment;

import cn.hutool.json.JSONUtil;
import com.hmdp.voucher.payment.config.PayProperties;
import com.hmdp.voucher.payment.domain.PayProvider;
import com.hmdp.voucher.payment.notify.WechatNotifyParser;
import com.hmdp.voucher.payment.support.WechatNotificationFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信回调「接线正确性」单测（<b>用真实 SDK，零网络</b>）。
 *
 * <p>微信支付没有公开沙箱，发单必须等商户号，所以<b>本测试是离线能拿到的唯一强证据</b>。
 * 刻意不复测 SDK 的密码学（那是微信的事），只证明<b>我们把参数填对了</b>：
 * 验签用的是「微信支付公钥 + 对应公钥 ID」、解密用的是正确的 APIv3 密钥、
 * 金额「分」没有被多加一次换算、拒绝路径不会产出命令。
 *
 * <p>零网络的前提：{@code NotificationParser.parse} 只做本地验签 + AES-GCM 解密，
 * 不发任何请求；配置用的是静态 {@code RSAPublicKeyNotificationConfig}，
 * <b>不碰 {@code RSAAutoCertificateConfig}</code>（那个构造时就联网下证书）</b>。
 */
class WechatNotifyWiringTest {

    private static WechatNotificationFixture fixture;

    @BeforeAll
    static void initFixture() throws Exception {
        fixture = new WechatNotificationFixture();
    }

    private static PayProperties validProperties() {
        PayProperties p = new PayProperties();
        PayProperties.Wechat w = p.getWechat();
        w.setAppId(WechatNotificationFixture.APP_ID);
        w.setMchId(WechatNotificationFixture.MCH_ID);
        w.setApiV3Key(WechatNotificationFixture.API_V3_KEY);
        w.setWechatPayPublicKeyId(WechatNotificationFixture.PUBLIC_KEY_ID);
        w.setWechatPayPublicKey(fixture.publicKeyPem());
        w.setNotifyUrl(WechatNotificationFixture.NOTIFY_URL);
        // 刻意不填 merchantPrivateKeyPath / merchantSerialNo：回调验签不需要商户私钥，
        // 见 PayProperties.Wechat#isNotifyConfigured()
        return p;
    }

    private static PayProperties propertiesWith(String apiV3Key) {
        PayProperties p = validProperties();
        p.getWechat().setApiV3Key(apiV3Key);
        return p;
    }

    private WechatNotifyParser.ParseResult parse(WechatNotifyParser parser, String body) throws Exception {
        return parser.parse(WechatNotificationFixture.PUBLIC_KEY_ID,
                fixture.headerNonce(), fixture.headerTimestamp(),
                fixture.sign(fixture.headerTimestamp(), fixture.headerNonce(), body), body);
    }

    // ==================== 正路径 ====================

    @Test
    @DisplayName("合法通知 → 验签解密通过，金额保持「分」，状态映射正确，原文留存")
    void validNotificationIsAccepted() throws Exception {
        WechatNotifyParser parser = new WechatNotifyParser(validProperties());
        String body = fixture.body("PAY1001", "4200001234567890", "SUCCESS", 1000L);

        WechatNotifyParser.ParseResult result = parse(parser, body);

        assertTrue(result.accepted(), "验签解密应通过：" + result.rejectReason());
        assertNotNull(result.command());
        assertEquals("PAY1001", result.command().getOutTradeNo());
        assertEquals("4200001234567890", result.command().getTradeNo());
        assertEquals(1000L, result.command().getAmount(), "微信 total 就是分，绝不换算");
        assertEquals("SUCCESS", result.command().getRawStatus());
        assertTrue(result.command().isSuccess());
        assertEquals(PayProvider.WECHAT, result.command().getProvider());
        assertNotNull(result.command().getNotifyTime());
        assertEquals(body, result.command().getPayload(), "留档必须是原始报文，才能离线复验签名");
    }

    @Test
    @DisplayName("NOTPAY：验签通过但不算成功（由业务层决定不推进）")
    void notpayParsesButIsNotSuccess() throws Exception {
        WechatNotifyParser parser = new WechatNotifyParser(validProperties());
        String body = fixture.body("PAY1001", "4200001234567890", "NOTPAY", 1000L);

        WechatNotifyParser.ParseResult result = parse(parser, body);

        assertTrue(result.accepted());
        assertFalse(result.command().isSuccess());
        assertEquals("NOTPAY", result.command().getRawStatus());
    }

    @Test
    @DisplayName("REFUND：既不算成功也不算关闭 —— 绝不推进（钱动过但订单不该变可用）")
    void refundIsAcceptedButNotSuccess() throws Exception {
        WechatNotifyParser parser = new WechatNotifyParser(validProperties());
        String body = fixture.body("PAY1001", "4200001234567890", "REFUND", 1000L);

        WechatNotifyParser.ParseResult result = parse(parser, body);

        assertTrue(result.accepted());
        assertFalse(result.command().isSuccess(), "REFUND 不能当成功，否则用户没付钱却拿到券");
    }

    // ==================== 拒绝路径 ====================

    @Test
    @DisplayName("签名被改一个字符 → 拒绝，且不产出命令（绝不能落到业务层）")
    void tamperedSignatureIsRejected() throws Exception {
        WechatNotifyParser parser = new WechatNotifyParser(validProperties());
        String body = fixture.body("PAY1001", "4200001234567890", "SUCCESS", 1000L);
        String signature = fixture.sign(fixture.headerTimestamp(), fixture.headerNonce(), body);

        WechatNotifyParser.ParseResult result = parser.parse(
                WechatNotificationFixture.PUBLIC_KEY_ID, fixture.headerNonce(),
                fixture.headerTimestamp(), signature.substring(0, signature.length() - 4) + "AAAA", body);

        assertFalse(result.accepted());
        assertTrue(result.rejectReason().startsWith("validation_failed"), result.rejectReason());
        assertNull(result.command(), "拒绝时不得产出命令");
    }

    @Test
    @DisplayName("body 被篡改（签名不变）→ 拒绝：证明 body 真的参与了验签")
    void tamperedBodyIsRejected() throws Exception {
        WechatNotifyParser parser = new WechatNotifyParser(validProperties());
        String body = fixture.body("PAY1001", "4200001234567890", "SUCCESS", 1000L);
        String signature = fixture.sign(fixture.headerTimestamp(), fixture.headerNonce(), body);
        // 把金额从 1000 分改成 1 分（最典型的资损篡改），签名仍是原来那份
        String tampered = fixture.body("PAY1001", "4200001234567890", "SUCCESS", 1L);

        WechatNotifyParser.ParseResult result = parser.parse(
                WechatNotificationFixture.PUBLIC_KEY_ID, fixture.headerNonce(),
                fixture.headerTimestamp(), signature, tampered);

        assertFalse(result.accepted(), "body 被改后验签必须失败");
        assertTrue(result.rejectReason().startsWith("validation_failed"), result.rejectReason());
    }

    @Test
    @DisplayName("Wechatpay-Serial 与配置的公钥 ID 不匹配 → 拒绝")
    void serialMismatchIsRejected() throws Exception {
        WechatNotifyParser parser = new WechatNotifyParser(validProperties());
        String body = fixture.body("PAY1001", "4200001234567890", "SUCCESS", 1000L);

        WechatNotifyParser.ParseResult result = parser.parse("PUB_KEY_ID_OTHER",
                fixture.headerNonce(), fixture.headerTimestamp(),
                fixture.sign(fixture.headerTimestamp(), fixture.headerNonce(), body), body);

        assertFalse(result.accepted());
        assertTrue(result.rejectReason().startsWith("validation_failed"), result.rejectReason());
    }

    @Test
    @DisplayName("APIv3 密钥配错 → 解密失败被拒（而不是把密文当明文用）")
    void wrongApiV3KeyIsRejected() throws Exception {
        WechatNotifyParser parser = new WechatNotifyParser(
                propertiesWith("00000000000000000000000000000000"));
        String body = fixture.body("PAY1001", "4200001234567890", "SUCCESS", 1000L);

        WechatNotifyParser.ParseResult result = parse(parser, body);

        assertFalse(result.accepted());
        assertTrue(result.rejectReason().startsWith("decrypt_failed"), result.rejectReason());
    }

    @Test
    @DisplayName("凭据未配置 → 一律拒绝（绝不能「没配公钥就先放过去」）")
    void unconfiguredRejectsEverything() throws Exception {
        WechatNotifyParser parser = new WechatNotifyParser(new PayProperties());
        String body = fixture.body("PAY1001", "4200001234567890", "SUCCESS", 1000L);

        WechatNotifyParser.ParseResult result = parse(parser, body);

        assertFalse(result.accepted());
        assertEquals("wechat_not_configured", result.rejectReason());
    }

    @Test
    @DisplayName("空报文 / 缺请求头 → 各自给出明确原因")
    void missingInputsAreRejected() throws Exception {
        WechatNotifyParser parser = new WechatNotifyParser(validProperties());
        String body = fixture.body("PAY1001", "4200001234567890", "SUCCESS", 1000L);
        String signature = fixture.sign(fixture.headerTimestamp(), fixture.headerNonce(), body);

        assertEquals("empty_body", parser.parse("S", "N", "T", "s", "  ").rejectReason());
        assertEquals("empty_body", parser.parse("S", "N", "T", "s", null).rejectReason());
        assertEquals("missing_serial", parser.parse(null, "N", "T", signature, body).rejectReason());
        assertEquals("missing_serial", parser.parse(" ", "N", "T", signature, body).rejectReason());
        assertEquals("missing_nonce", parser.parse("S", " ", "T", signature, body).rejectReason());
        assertEquals("missing_timestamp", parser.parse("S", "N", " ", signature, body).rejectReason());
        assertEquals("missing_signature", parser.parse("S", "N", "T", " ", body).rejectReason());
    }

    /**
     * 这张表是「与支付宝口径刻意不同」的证据：微信只有 {@code SUCCESS} 算已收款，
     * 而 {@code REFUND} 三种判定都不落（既非成功、也非关闭）→ 交给人工。
     */
    @Test
    @DisplayName("状态语义表：微信只有 SUCCESS 算已收款，REFUND 三种判定都不落")
    void tradeStateSemantics() {
        assertTrue(WechatTradeState.isPaid("SUCCESS"));
        assertFalse(WechatTradeState.isPaid("REFUND"), "退款不是成功");
        assertFalse(WechatTradeState.isPaid("TRADE_FINISHED"), "那是支付宝的状态串，不该被微信侧认可");

        assertTrue(WechatTradeState.isClosed("CLOSED"));
        assertTrue(WechatTradeState.isClosed("REVOKED"));
        assertTrue(WechatTradeState.isClosed("PAYERROR"));
        assertFalse(WechatTradeState.isClosed("REFUND"), "退款不能当关闭处理，否则抹掉「钱动过」的事实");

        assertTrue(WechatTradeState.isAnomaly("REFUND"));
        assertFalse(WechatTradeState.isAnomaly("SUCCESS"));
        assertFalse(WechatTradeState.isAnomaly("NOTPAY"));
    }

    @Test
    @DisplayName("isNotifyConfigured 只要求公钥三件套，不要求商户私钥（回调要比发单更健壮）")
    void notifyConfiguredDoesNotRequireMerchantPrivateKey() {
        PayProperties.Wechat w = validProperties().getWechat();

        assertTrue(w.isNotifyConfigured());
        assertFalse(w.isConfigured(), "缺 appId/私钥路径/证书序列号 → 不能算「可以发起支付」");

        w.setAppId(WechatNotificationFixture.APP_ID);
        w.setMerchantPrivateKeyPath("/tmp/not-exists.pem");
        w.setMerchantSerialNo("SERIALNO1");
        assertTrue(w.isConfigured(), "补齐后可发起支付");
        assertTrue(w.isNotifyConfigured(), "而且并未因此影响回调判定");
    }

    /**
     * 夹具自身的契约：证明它<b>真的做了加密</b>。
     *
     * <p>如果哪天夹具退化成了「明文塞进 body」，上面所有「验签解密通过」的断言都会变成假绿 ——
     * 因为那种 body 也能被解析出来。所以必须钉住「敏感字段不在明文里」。
     */
    @Test
    @DisplayName("夹具契约：商户单号只以密文形式出现，body 里没有明文")
    void fixtureBodyIsActuallyEncrypted() {
        String body = fixture.body("PAY1001", "4200001234567890", "SUCCESS", 1000L);

        Map<String, Object> parsed = JSONUtil.parseObj(body);
        assertEquals("TRANSACTION.SUCCESS", parsed.get("event_type"));
        Map<?, ?> resource = (Map<?, ?>) parsed.get("resource");
        assertNotNull(resource);
        assertEquals("AEAD_AES_256_GCM", resource.get("algorithm"));
        assertNotNull(resource.get("ciphertext"));
        assertFalse(body.contains("PAY1001"), "商户单号出现在明文里，说明夹具没加密");
        assertFalse(body.contains("4200001234567890"), "交易号出现在明文里，说明夹具没加密");
    }
}
