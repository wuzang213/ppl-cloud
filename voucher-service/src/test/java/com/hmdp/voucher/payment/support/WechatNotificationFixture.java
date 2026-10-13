package com.hmdp.voucher.payment.support;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

/**
 * 造一份「和微信真发过来的长得一样」的支付通知。
 *
 * <p>微信回调没有公开沙箱，所以离线能拿到的最强证据就是<b>自己按协议造一份合法报文</b>，
 * 它能同时证明两件事：我们的验签/解密装配是对的；我们把 SDK 的参数填对了。
 *
 * <p>构造过程与微信侧完全对齐（三步都按官方协议，不抄近道）：
 * <ol>
 *   <li>{@code resource.plaintext} —— 交易对象的 JSON；</li>
 *   <li>{@code resource} —— 上面那段明文用 <b>AES-256-GCM</b> 加密，密钥是 APIv3 密钥、
 *       nonce 12 字节、有 associated_data，密文按 {@code base64(ciphertext || tag)} 编码；</li>
 *   <li>{@code Wechatpay-Signature} —— 对 {@code timestamp\nnonce\nbody\n} 做 SHA256withRSA 并 base64。</li>
 * </ol>
 * 注意第 3 步里的 {@code nonce} 是<b>报文头</b>的 nonce，与第 2 步 resource 里的 nonce 是两个东西 ——
 * 这一点经常被写混，混了就会「怎么都验不过」。
 */
public class WechatNotificationFixture {

    /** 32 字节 APIv3 密钥（AES-256-GCM 的密钥长度） */
    public static final String API_V3_KEY = "hmdp0000hmdp0000hmdp0000hmdp0000";
    public static final String MCH_ID = "1900000001";
    public static final String APP_ID = "wx8888888888888888";
    public static final String PUBLIC_KEY_ID = "PUB_KEY_ID_0001";
    public static final String NOTIFY_URL = "https://example.com/voucher-order/pay/notify/wechat";

    private static final String HEADER_NONCE = "hihihi123456";
    private static final String HEADER_TIMESTAMP = "1789000000";
    private static final String ASSOCIATED_DATA = "transaction";

    private final KeyPair keyPair;
    private final String publicKeyPem;

    public WechatNotificationFixture() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.keyPair = generator.generateKeyPair();
        this.publicKeyPem = toPem(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
    }

    /** 微信支付公钥（PEM）。SDK 用 {@code PemUtil.loadPublicKeyFromString} 解析，必须带 PEM 头尾。 */
    public String publicKeyPem() {
        return publicKeyPem;
    }

    /**
     * 本夹具密钥对的私钥（Base64 PKCS#8，无 PEM 头尾）。
     *
     * <p>在真实世界里这个私钥属于<b>微信侧</b>（只有它有资格签回调）。
     * 测试里把它当作 {@code hmdp.pay.mock.sign-private-key} 使用 ——
     * 模拟「渠道侧拿自己的私钥签，我们用配好的公钥验」这个真实关系。
     */
    public String privateKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    public String headerNonce() {
        return HEADER_NONCE;
    }

    public String headerTimestamp() {
        return HEADER_TIMESTAMP;
    }

    /** 对一份 body 产出发给我们的签名（模拟微信侧用私钥签）。 */
    public String sign(String timestamp, String nonce, String body) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update((timestamp + "\n" + nonce + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    /**
     * 造一份 {@code TRANSACTION.SUCCESS} 通知的原始 body。
     *
     * @param totalFen 金额（分）—— 微信本来就用「分」，这里不换算，用于证明我们没有多乘/少乘 100 倍
     */
    public String body(String outTradeNo, String tradeNo, String tradeState, long totalFen) {
        JSONObject amount = JSONUtil.createObj()
                .set("total", totalFen)
                .set("payer_total", totalFen)
                .set("currency", "CNY")
                .set("payer_currency", "CNY");
        JSONObject transaction = JSONUtil.createObj()
                .set("appid", APP_ID)
                .set("mchid", MCH_ID)
                .set("out_trade_no", outTradeNo)
                .set("transaction_id", tradeNo)
                .set("trade_type", "NATIVE")
                .set("trade_state", tradeState)
                .set("success_time", "2026-09-26T20:00:00+08:00")
                .set("amount", amount);

        return JSONUtil.createObj()
                .set("id", "EV-2026092622000000000001")
                .set("create_time", "2026-09-26T20:00:01+08:00")
                .set("resource_type", "encrypt-resource")
                .set("event_type", "TRANSACTION.SUCCESS")
                .set("summary", "支付成功")
                .set("resource", encryptedResource(JSONUtil.toJsonStr(transaction)))
                .toString();
    }

    private JSONObject encryptedResource(String plaintext) {
        String nonce = HEADER_NONCE; // 与头部 nonce 取同一个值只是图方便，两者本无关联
        return JSONUtil.createObj()
                .set("algorithm", "AEAD_AES_256_GCM")
                .set("ciphertext", aesGcmEncrypt(plaintext, API_V3_KEY, nonce, ASSOCIATED_DATA))
                .set("nonce", nonce)
                .set("associated_data", ASSOCIATED_DATA);
    }

    /** 与微信一致：{@code base64(ciphertext || tag)}，tag 16 字节（GCM 的 128 位认证标签）。 */
    private static String aesGcmEncrypt(String plaintext, String apiV3Key, String nonce, String associatedData) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec key = new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES");
            GCMParameterSpec spec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("测试夹具加密失败", e);
        }
    }

    private static String toPem(String base64Der) {
        StringBuilder sb = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < base64Der.length(); i += 64) {
            sb.append(base64Der, i, Math.min(i + 64, base64Der.length())).append('\n');
        }
        return sb.append("-----END PUBLIC KEY-----\n").toString();
    }
}
