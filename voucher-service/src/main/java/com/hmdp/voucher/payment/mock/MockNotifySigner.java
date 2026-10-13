package com.hmdp.voucher.payment.mock;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alipay.api.internal.util.AlipaySignature;
import com.hmdp.voucher.payment.AlipayAmount;
import com.hmdp.voucher.payment.AlipayTradeStatus;
import com.hmdp.voucher.payment.WechatTradeState;
import com.hmdp.voucher.payment.config.PayProperties;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** 造「和渠道真发过来的长得一样」的支付通知，供 mock 渠道在验收期把业务主干端到端跑通。本项目没有公网域名（渠道要求
 * {@code notify_url} 公网可达 https）也没有商户凭据，「回调 → 验签 → 状态机 → 订单 → outbox」只能靠自造合法报文验证；
 * 它证明「本系统的验签装配与状态机是对的」，不能证明「渠道侧协议理解无误」。加签用 {@code hmdp.pay.mock.sign-private-key}、验签用渠道配置里的公钥，故前提是把对应公钥填到 {@code hmdp.pay.alipay.alipay-public-key}（Base64 无头尾）与 {@code hmdp.pay.wechat.wechat-pay-public-key}（PEM 带头尾）；两侧是否配对由 {@code PayMockGuard} 启动时校验（不配对直接拒绝启动，否则症状是「回调全部验签失败」而日志看不出是密钥填错）。{@code src/test} 下的 Fixture 是独立的第二实现、不复用本类，这样「假报文能过真实验签」才是有意义的交叉验证。本类不是生产渠道：只有 {@code hmdp.pay.mock.enabled=true} 时才装配，生产 profile 下开启会导致启动失败。 */
public final class MockNotifySigner {

    /** 微信 {@code resource} 的附加数据（官方交易通知固定为 {@code transaction}） */
    private static final String ASSOCIATED_DATA = "transaction";

    /** GCM 认证标签长度（位）——微信用 128 位 */
    private static final int GCM_TAG_BITS = 128;

    /** {@code resource.nonce} 长度（字节）——12 字节是 GCM 的推荐值 */
    private static final int RESOURCE_NONCE_BYTES = 12;

    /** 回调头部 {@code Wechatpay-Nonce} 的长度（官方示例为 32 字符） */
    private static final int HEADER_NONCE_LENGTH = 32;

    /** mock 虚拟交易号前缀：让「这笔是 mock 造的」在库里一眼可辨 */
    private static final String VIRTUAL_TRADE_NO_PREFIX = "MOCK";

    private MockNotifySigner() {
    }

    // ==================================================================
    // 支付宝
    // ==================================================================

    /** 造一份支付宝异步通知的表单参数（已用 mock 私钥按配置的 {@code sign-type} 加签）。字段与支付宝真实报文对齐到
     * 「够 {@code AlipayNotifyParser} 走完全部校验」的程度：{@code sign} → {@code app_id} → {@code out_trade_no} /
     * {@code trade_status} / {@code total_amount}。@param success false 时给出 {@code WAIT_BUYER_PAY}，用于验证「未支付的通知不推进也不告警」 */
    public static Map<String, String> alipayNotify(PayProperties cfg, String outTradeNo,
                                                   long amountFen, boolean success) {
        PayProperties.Alipay alipay = cfg.getAlipay();
        String privateKey = require(cfg.getMock().getSignPrivateKey(), "hmdp.pay.mock.sign-private-key");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", require(alipay.getAppId(), "hmdp.pay.alipay.app-id"));
        params.put("out_trade_no", require(outTradeNo, "outTradeNo"));
        params.put("trade_no", virtualTradeNo(outTradeNo));
        params.put("trade_status", success ? AlipayTradeStatus.TRADE_SUCCESS : AlipayTradeStatus.WAIT_BUYER_PAY);
        params.put("total_amount", AlipayAmount.fenToYuanString(amountFen));
        params.put("subject", "黑马点评团购券(mock)");
        signAlipay(params, privateKey, alipay.getCharset(), alipay.getSignType());
        return params;
    }

    /** 用 mock 私钥给支付宝参数加签，写入 {@code sign} / {@code sign_type}。必须显式传 signType：
     * {@code AlipaySignature.rsaSign(Map, key, charset)} 三参重载内部落到不带 signType 的重载、默认 {@code RSA}(SHA1)，
     * 而验签用的是配置的 {@code RSA2}(SHA256) —— 两者混用会永远验不过，且报错只有一句「验签失败」。 */
    public static void signAlipay(Map<String, String> params, String privateKeyBase64,
                                  String charset, String signType) {
        try {
            params.remove("sign");
            params.remove("sign_type");
            String content = AlipaySignature.getSignContent(params);
            params.put("sign", AlipaySignature.rsaSign(content, privateKeyBase64,
                    charset, signType == null || signType.isBlank() ? "RSA2" : signType));
            params.put("sign_type", signType == null || signType.isBlank() ? "RSA2" : signType);
        } catch (Exception e) {
            throw new IllegalStateException("mock 支付宝回调加签失败：" + e.getMessage(), e);
        }
    }

    // ==================================================================
    // 微信
    // ==================================================================

    /** 一份微信支付通知的完整投递材料。
     * @param body 原始请求体（{@code resource} 里是 AES-256-GCM 密文）@param serial 请求头 {@code Wechatpay-Serial}（= 配置的 {@code wechat-pay-public-key-id}）
     * @param nonce 请求头 {@code Wechatpay-Nonce}（注意与 {@code resource.nonce} 不是一个东西）@param timestamp 请求头 {@code Wechatpay-Timestamp} @param signature 请求头 {@code Wechatpay-Signature} */
    public record WechatNotify(String body, String serial, String nonce, String timestamp, String signature) {

        /** 按微信规范拼出 4 个请求头。 */
        public Map<String, String> headers() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Wechatpay-Serial", serial);
            headers.put("Wechatpay-Nonce", nonce);
            headers.put("Wechatpay-Timestamp", timestamp);
            headers.put("Wechatpay-Signature", signature);
            return headers;
        }
    }

    /** 造一份微信支付通知（签名合法 + {@code resource} 已按 AES-256-GCM 加密）。构造三步与微信侧完全对齐：交易对象序列化成
     * {@code resource.plaintext}；用 APIv3 密钥 AES-256-GCM 加密、密文按 {@code base64(ciphertext || tag)} 编码；对
     * {@code timestamp\nnonce\nbody\n} 做 SHA256withRSA 并 base64。@param success false 时给出 {@code NOTPAY}，并省略 {@code success_time} */
    public static WechatNotify wechatNotify(PayProperties cfg, String outTradeNo,
                                            long amountFen, boolean success) {
        PayProperties.Wechat wechat = cfg.getWechat();
        String privateKey = require(cfg.getMock().getSignPrivateKey(), "hmdp.pay.mock.sign-private-key");
        String apiV3Key = require(wechat.getApiV3Key(), "hmdp.pay.wechat.api-v3-key");

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String headerNonce = randomToken(HEADER_NONCE_LENGTH);
        String body = wechatBody(wechat, outTradeNo, amountFen, success, apiV3Key);

        return new WechatNotify(
                body,
                require(wechat.getWechatPayPublicKeyId(), "hmdp.pay.wechat.wechat-pay-public-key-id"),
                headerNonce,
                timestamp,
                signWechat(timestamp, headerNonce, body, privateKey));
    }

    /** 对 {@code timestamp\nnonce\nbody\n} 做 SHA256withRSA（微信的签名口径）。 */
    public static String signWechat(String timestamp, String nonce, String body, String privateKeyBase64) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(loadPrivateKey(privateKeyBase64));
            // 尾部那个 \n 是协议的一部分，少一个字符就永远验不过
            signer.update((timestamp + "\n" + nonce + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("mock 微信回调加签失败：" + e.getMessage(), e);
        }
    }

    private static String wechatBody(PayProperties.Wechat cfg, String outTradeNo,
                                     long amountFen, boolean success, String apiV3Key) {
        // 微信 amount.total 本来就是「分」，与 tb_payment.amount 同口径，不做任何换算
        JSONObject amount = JSONUtil.createObj()
                .set("total", amountFen)
                .set("payer_total", amountFen)
                .set("currency", "CNY")
                .set("payer_currency", "CNY");
        JSONObject transaction = JSONUtil.createObj()
                .set("appid", cfg.getAppId())
                .set("mchid", cfg.getMchId())
                .set("out_trade_no", outTradeNo)
                .set("transaction_id", virtualTradeNo(outTradeNo))
                .set("trade_type", "NATIVE")
                .set("trade_state", success ? WechatTradeState.SUCCESS : WechatTradeState.NOTPAY)
                .set("amount", amount);
        if (success) {
            transaction.set("success_time", OffsetDateTime.now().toString());
        }

        String resourceNonce = randomToken(RESOURCE_NONCE_BYTES);
        JSONObject resource = JSONUtil.createObj()
                .set("algorithm", "AEAD_AES_256_GCM")
                .set("ciphertext", aesGcmEncrypt(JSONUtil.toJsonStr(transaction),
                        apiV3Key, resourceNonce, ASSOCIATED_DATA))
                .set("nonce", resourceNonce)
                .set("associated_data", ASSOCIATED_DATA);

        return JSONUtil.createObj()
                .set("id", "EV-" + System.nanoTime())
                .set("create_time", OffsetDateTime.now().toString())
                .set("resource_type", "encrypt-resource")
                .set("event_type", success ? "TRANSACTION.SUCCESS" : "TRANSACTION.CLOSED")
                .set("summary", success ? "支付成功" : "支付未完成")
                .set("resource", resource)
                .toString();
    }

    /** 与微信一致：{@code base64(ciphertext || tag)}，tag 16 字节（128 位）。 */
    private static String aesGcmEncrypt(String plaintext, String apiV3Key, String nonce, String associatedData) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec key = new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, nonce.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 最常见原因是 apiV3Key 不是 32 字节 —— 那种情况下 GCM 初始化就会失败
            throw new IllegalStateException("mock 微信回执加密失败（检查 api-v3-key 是否为 32 字节）："
                    + e.getMessage(), e);
        }
    }

    // ==================================================================
    // 公共
    // ==================================================================

    /** mock 虚拟交易号。刻意做得「像真的但不是真的」：长度与渠道侧同量级（{@code trade_no} 列宽 64），
     * 但带 {@value #VIRTUAL_TRADE_NO_PREFIX} 前缀，验收后查库能立刻分辨哪些是 mock 造的单，不至于在真实对账里混淆。 */
    public static String virtualTradeNo(String outTradeNo) {
        return VIRTUAL_TRADE_NO_PREFIX + outTradeNo;
    }

    /** 解析 Base64（PKCS#8，无 PEM 头尾）的 RSA 私钥。 */
    public static PrivateKey loadPrivateKey(String privateKeyBase64) {
        try {
            byte[] der = Base64.getDecoder().decode(privateKeyBase64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("mock 私钥解析失败（应为 PKCS#8 Base64，无 PEM 头尾）："
                    + e.getMessage(), e);
        }
    }

    private static String randomToken(int length) {
        // 用 base64url 字母表生成纯 ASCII，保证「字符数 == 字节数」，避免 nonce 长度踩坑
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String require(String value, String configKey) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("mock 渠道缺少配置 " + configKey + "，无法造出合法回调");
        }
        return value;
    }
}
