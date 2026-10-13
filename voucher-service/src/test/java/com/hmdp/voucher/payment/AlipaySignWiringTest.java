package com.hmdp.voucher.payment;

import com.alipay.api.internal.util.AlipaySignature;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.channel.AlipayPageChannel;
import com.hmdp.voucher.payment.config.PayProperties;
import com.hmdp.voucher.payment.domain.PayProvider;
import com.hmdp.voucher.payment.domain.PrepayResult;
import com.hmdp.voucher.payment.notify.AlipayNotifyParser;
import com.hmdp.voucher.payment.support.AlipayNotificationFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付宝「接线正确性」单测（<b>用真实 SDK，零网络</b>）。
 *
 * <p>刻意<b>不复测 SDK 的密码学</b>（那是支付宝的事），只证明<b>我们的调用参数是对的</b> ——
 * 密钥格式（PKCS#8 / X.509）、{@code sign_type=RSA2}、{@code charset} 一致性、
 * 以及「分 → 元」换算有没有接上。这几处才是真实事故来源，而且错了都不报错。
 *
 * <p>零网络的前提：{@code pageExecute(request, "GET")} 只拼参数加签，不发请求；
 * {@code rsaSign} / {@code rsaCheckV1} 是纯本地计算。
 */
class AlipaySignWiringTest {

    private static final String GATEWAY = AlipayNotificationFixture.GATEWAY;

    private static AlipayNotificationFixture fixture;

    @BeforeAll
    static void initFixture() throws Exception {
        fixture = new AlipayNotificationFixture();
    }

    private static AlipayNotifyParser parser() {
        return new AlipayNotifyParser(fixture.properties());
    }

    // ==================== 预下单接线 ====================

    @Test
    @DisplayName("pageExecute 生成的收银台 URL：金额是「元」两位小数，不是「分」")
    void pageExecuteUrlCarriesYuanAmount() throws Exception {
        AlipayPageChannel channel = new AlipayPageChannel(fixture.properties());
        Payment payment = new Payment()
                .setId(1L).setOrderId(20002L).setAmount(1000L).setFaceValue(10000L)
                .setOutTradeNo("PAY1001").setChannel(channel.code());

        PrepayResult result = channel.prepay(payment);

        String url = result.getPayload();
        assertNotNull(url);
        assertTrue(url.startsWith(GATEWAY), "应指向配置的网关: " + url);
        // 金额在 biz_content 里且整体被 URL 编码，必须先解码再断言，否则看不出真实取值
        String decoded = URLDecoder.decode(url, "UTF-8");
        assertTrue(decoded.contains("\"total_amount\":\"10.00\""),
                "1000 分必须换算成 10.00 元: " + decoded);
        assertFalse(decoded.contains("\"total_amount\":\"1000\""),
                "绝不能把分当元传（会静默多收 100 倍）");
        assertTrue(decoded.contains("\"out_trade_no\":\"PAY1001\""), "商户单号必须带上");
        assertTrue(url.contains("sign="), "必须已加签");
        assertTrue(url.contains("sign_type=RSA2"));
        assertEquals("url", result.getKind());
    }

    @Test
    @DisplayName("biz_content 的关键字段：金额、超时、产品码")
    void bizContentFields() {
        String biz = AlipayPageChannel.buildPrepayBizContent(1000L, "PAY1001", 20002L, 5);

        assertTrue(biz.contains("\"total_amount\":\"10.00\""), biz);
        assertTrue(biz.contains("\"timeout_express\":\"5m\""), biz);
        assertTrue(biz.contains("\"product_code\":\"FAST_INSTANT_TRADE_PAY\""), biz);
        assertTrue(biz.contains("\"out_trade_no\":\"PAY1001\""), biz);
    }

    @Test
    @DisplayName("元 → 分 反解：两位小数严格，无法解析时返回 null（不许猜）")
    void parseYuanToFenIsStrict() {
        assertEquals(Long.valueOf(1000L), AlipayAmount.yuanToFen("10.00"));
        assertEquals(Long.valueOf(1L), AlipayAmount.yuanToFen("0.01"));
        assertEquals(Long.valueOf(123456789L), AlipayAmount.yuanToFen("1234567.89"));
        assertEquals(Long.valueOf(0L), AlipayAmount.yuanToFen("0"));
        assertNull(AlipayAmount.yuanToFen("10.001"), "超过两位小数不得静默截断");
        assertNull(AlipayAmount.yuanToFen("abc"));
        assertNull(AlipayAmount.yuanToFen(null));
        assertNull(AlipayAmount.yuanToFen("  "));
    }

    @Test
    @DisplayName("凭据不齐时 isReady=false（用于区分「渠道未配置」与「渠道不存在」）")
    void isReadyReflectsCredentials() {
        assertTrue(new AlipayPageChannel(fixture.properties()).isReady());
        assertFalse(new AlipayPageChannel(new PayProperties()).isReady(), "空配置必须报告未就绪");
    }

    // ==================== 回调验签接线 ====================

    @Test
    @DisplayName("合法签名 → 解析通过，金额换算成「分」、状态映射正确")
    void validNotifyIsAccepted() throws Exception {
        AlipayNotifyParser.ParseResult result =
                parser().parse(fixture.successNotify("10.00", "PAY1001"));

        assertTrue(result.accepted(), "验签应通过：" + result.rejectReason());
        assertNotNull(result.command());
        assertEquals("PAY1001", result.command().getOutTradeNo());
        assertEquals(1000L, result.command().getAmount(), "total_amount 10.00 元 → 1000 分");
        assertEquals("TRADE_SUCCESS", result.command().getRawStatus());
        assertTrue(result.command().isSuccess());
        assertEquals(PayProvider.ALIPAY, result.command().getProvider());
        assertEquals("2026092622001400000000000001", result.command().getTradeNo());
        assertNotNull(result.command().getPayload(), "必须留存原文");
        assertTrue(result.command().getPayload().contains("sign="), "原文里要留下当时的签名");
    }

    @Test
    @DisplayName("验签不得吃掉调用方 Map 里的 sign（SDK 会就地 remove，必须传副本）")
    void verifyMustNotMutateCallerMap() throws Exception {
        AlipayNotifyParser parser = parser();
        Map<String, String> params = fixture.successNotify("10.00", "PAY1001");
        String signBefore = params.get("sign");
        assertNotNull(signBefore);

        parser.parse(params);

        // AlipaySignature.getSignCheckContentV1 会就地 remove("sign")/remove("sign_type")。
        // 一旦把原 Map 直接递进去，留档原文里就永远没有签名了 —— 对账时无从比对。
        assertEquals(signBefore, params.get("sign"), "调用方 Map 被 SDK 改坏了");
        assertEquals("RSA2", params.get("sign_type"));
    }

    @Test
    @DisplayName("改动报文任意一处 → 验签失败（金额被改一位是典型资损场景）")
    void tamperedAmountIsRejected() throws Exception {
        Map<String, String> params = fixture.successNotify("10.00", "PAY1001");
        params.put("total_amount", "0.01");

        AlipayNotifyParser.ParseResult result = parser().parse(params);
        assertFalse(result.accepted(), "金额被篡改后验签必须失败");
        assertEquals("sign_verify_failed", result.rejectReason());
        assertNull(result.command(), "拒绝时不得产出命令，绝不能落到业务层");
    }

    @Test
    @DisplayName("签名时 charset 与验签配置不一致 → 失败（报文含中文才能暴露）")
    void charsetMismatchIsRejected() throws Exception {
        // 签名用 GBK，验签配置是 UTF-8；报文里有中文，两种编码字节不同 → 必然失败
        Map<String, String> params = fixture.successNotify("10.00", "PAY1001");
        fixture.sign(params, "GBK");

        assertFalse(parser().parse(params).accepted(), "charset 不一致必须导致验签失败");
    }

    @Test
    @DisplayName("换成另一对密钥的公钥 → 失败")
    void wrongPublicKeyIsRejected() throws Exception {
        AlipayNotifyParser parser =
                new AlipayNotifyParser(fixture.propertiesWithPublicKey(fixture.unrelatedPublicKey()));

        assertFalse(parser.parse(fixture.successNotify("10.00", "PAY1001")).accepted());
    }

    @Test
    @DisplayName("app_id 不匹配 → 拒绝（验签过了也不认别人的单）")
    void appIdMismatchIsRejected() throws Exception {
        Map<String, String> params = fixture.successNotify("10.00", "PAY1001");
        // 重新签名，让签名合法、但 app_id 不是我们的
        params.put("app_id", "9999999999999999");
        fixture.sign(params, "UTF-8");

        AlipayNotifyParser.ParseResult result = parser().parse(params);
        assertFalse(result.accepted());
        assertEquals("app_id_mismatch", result.rejectReason());
    }

    @Test
    @DisplayName("缺签名 / 缺商户单号 / 金额不可解析 → 各自拒绝")
    void missingFieldsAreRejected() throws Exception {
        AlipayNotifyParser parser = parser();

        Map<String, String> noSign = fixture.successNotify("10.00", "PAY1001");
        noSign.remove("sign");
        assertEquals("missing_sign", parser.parse(noSign).rejectReason());

        Map<String, String> noOutTradeNo = fixture.successNotify("10.00", "PAY1001");
        noOutTradeNo.remove("out_trade_no");
        fixture.sign(noOutTradeNo, "UTF-8");
        assertEquals("missing_out_trade_no", parser.parse(noOutTradeNo).rejectReason());

        assertTrue(parser.parse(fixture.successNotify("10.001", "PAY1001"))
                .rejectReason().startsWith("unparsable_total_amount"));
    }

    @Test
    @DisplayName("凭据未配置时回调一律拒绝（绝不能「没配公钥就先放过去」）")
    void unconfiguredAlipayRejectsEverything() throws Exception {
        AlipayNotifyParser parser = new AlipayNotifyParser(new PayProperties());

        AlipayNotifyParser.ParseResult result = parser.parse(fixture.successNotify("10.00", "PAY1001"));

        assertFalse(result.accepted());
        assertEquals("alipay_not_configured", result.rejectReason());
    }

    @Test
    @DisplayName("WAIT_BUYER_PAY：验签通过但不标记为成功（由业务层决定不推进）")
    void waitBuyerPayParsesButIsNotSuccess() throws Exception {
        Map<String, String> params = fixture.successNotify("10.00", "PAY1001");
        params.put("trade_status", "WAIT_BUYER_PAY");
        fixture.sign(params, "UTF-8");

        AlipayNotifyParser.ParseResult result = parser().parse(params);

        assertTrue(result.accepted());
        assertFalse(result.command().isSuccess());
        assertEquals("WAIT_BUYER_PAY", result.command().getRawStatus());
    }

    /** 保留一处对 SDK 加签/验签可用的显式契约，避免有人把签名方式换掉时无人察觉。 */
    @Test
    @DisplayName("SDK 契约：rsaSign 出的签名能被 rsaCheckV1 验过（算法必须同为 RSA2）")
    void rsaSignAndCheckAgree() throws Exception {
        Map<String, String> params = fixture.successNotify("10.00", "PAY1001");
        String sign = params.get("sign");

        // 传副本：rsaCheckV1 会就地 remove("sign")/remove("sign_type")
        assertTrue(AlipaySignature.rsaCheckV1(new LinkedHashMap<>(params),
                        fixture.alipayPublicKey, "UTF-8", "RSA2"),
                "同一套算法下必须验签通过");
        assertEquals(sign, params.get("sign"), "原 Map 不该被动过");
    }
}
