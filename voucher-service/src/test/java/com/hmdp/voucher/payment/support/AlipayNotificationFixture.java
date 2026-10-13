package com.hmdp.voucher.payment.support;

import com.alipay.api.internal.util.AlipaySignature;
import com.hmdp.voucher.payment.config.PayProperties;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 造一份「和支付宝真发过来的长得一样」的异步通知，以及配套的 {@link PayProperties}。
 *
 * <p>支付宝有沙箱，但沙箱发货需要公网可达的 {@code notify_url}，离线单测拿不到。
 * 所以离线能拿到的最强证据就是<b>自己按协议加签</b> —— 它能同时证明两件事：
 * 我们的验签装配是对的；我们把 SDK 的参数填对了。
 *
 * <p><b>加签必须显式指定 {@code RSA2}</b>：{@code AlipaySignature.rsaSign(Map, key, charset)}
 * 这个三参重载内部落到<b>不带 signType 的</b>重载，默认 {@code RSA}(SHA1)；
 * 而我们的验签配置是 {@code RSA2}(SHA256)。两者混用会<b>永远验不过</b>，
 * 且报错只有一句「验签失败」，看不出是签名算法不一致。
 */
public class AlipayNotificationFixture {

    public static final String APP_ID = "2021000000000000";
    public static final String GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    public static final String NOTIFY_URL = "https://example.com/voucher-order/pay/notify/alipay";
    public static final String RETURN_URL = "https://example.com/pay/return";

    /** 本夹具的密钥对私钥（Base64 PKCS#8），扮演「商户私钥」 */
    public final String merchantPrivateKey;

    /** 本夹具的密钥对公钥（Base64 X.509），在配置里扮演「支付宝公钥」 */
    public final String alipayPublicKey;

    private final KeyPair keyPair;

    public AlipayNotificationFixture() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.keyPair = generator.generateKeyPair();
        this.merchantPrivateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        this.alipayPublicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    /** 另一对密钥的公钥 —— 用于「换错公钥必须验签失败」这类用例。 */
    public String unrelatedPublicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
    }

    public PayProperties properties() {
        return propertiesWithPublicKey(alipayPublicKey);
    }

    public PayProperties propertiesWithPublicKey(String alipayPublicKey) {
        PayProperties p = new PayProperties();
        PayProperties.Alipay a = p.getAlipay();
        a.setAppId(APP_ID);
        a.setMerchantPrivateKey(merchantPrivateKey);
        a.setAlipayPublicKey(alipayPublicKey);
        a.setGatewayUrl(GATEWAY);
        a.setNotifyUrl(NOTIFY_URL);
        a.setReturnUrl(RETURN_URL);
        return p;
    }

    /** 一份「TRADE_SUCCESS + 指定金额」的合法通知（已加签）。 */
    public Map<String, String> successNotify(String totalAmount, String outTradeNo) throws Exception {
        return notify(totalAmount, outTradeNo, "TRADE_SUCCESS");
    }

    public Map<String, String> notify(String totalAmount, String outTradeNo, String tradeStatus) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", APP_ID);
        params.put("out_trade_no", outTradeNo);
        params.put("trade_no", "2026092622001400000000000001");
        params.put("trade_status", tradeStatus);
        params.put("total_amount", totalAmount);
        params.put("subject", "黑马点评团购券");
        sign(params, "UTF-8");
        return params;
    }

    /** 用夹具私钥按 {@code RSA2} 加签，并写入 {@code sign} / {@code sign_type}。 */
    public void sign(Map<String, String> params, String charset) throws Exception {
        signWith(params, charset, merchantPrivateKey);
    }

    /** 指定私钥加签（用于「用别人的密钥签、但 app_id 是我们的」这类用例）。 */
    public void signWith(Map<String, String> params, String charset, String privateKeyBase64) throws Exception {
        params.remove("sign");
        params.remove("sign_type");
        String content = AlipaySignature.getSignContent(params);
        params.put("sign", AlipaySignature.rsaSign(content, privateKeyBase64, charset, "RSA2"));
        params.put("sign_type", "RSA2");
    }
}
