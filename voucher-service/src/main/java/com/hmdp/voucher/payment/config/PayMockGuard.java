package com.hmdp.voucher.payment.config;

import com.hmdp.voucher.constants.VoucherConstants;
import com.hmdp.voucher.payment.mock.MockNotifySigner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/** 支付配置的启动期护栏：把「不报错但行为上悄悄坏掉」的配置问题变成启动失败。本类无条件装配（不受 {@code mock.enabled}
 * 影响），否则 mock 关闭时它也不存在、就没人能在 mock 打开的瞬间拦住它。始终校验 {@code hmdp.pay.prepay-ttl-minutes} ≤
 * 订单超时（15 分钟），否则出现「支付单还有效、订单已被关」窗口（用户付了钱而订单已取消，是资损场景，且不会报任何异常）；mock 启用时还校验当前 profile 在开发白名单内、mock 私钥与渠道两份公钥真的配对（不配对症状是「所有回调都验签失败」，日志里看不出是密钥填错）。 */
@Component
@Slf4j
public class PayMockGuard implements InitializingBean {

    /** 允许开启 mock 渠道的 profile 白名单（默认安全：不在名单里就拒绝）。刻意不放 {@code default}：Spring Boot 未显式
     * 指定 profile 时 {@code getActiveProfiles()} 返回空数组，「没声明自己是什么环境」不该被当成开发环境；本仓库
     * {@code bootstrap.yaml} 已显式写死 {@code spring.profiles.active: local}，因此正常开发不会受影响。 */
    private static final Set<String> MOCK_ALLOWED_PROFILES = Set.of("local", "dev", "test");

    private final Environment environment;
    private final PayProperties payProperties;

    public PayMockGuard(Environment environment, PayProperties payProperties) {
        this.environment = environment;
        this.payProperties = payProperties;
    }

    @Override
    public void afterPropertiesSet() {
        checkPrepayTtlWithinOrderTimeout();
        PayProperties.Mock mock = payProperties.getMock();
        if (!mock.isEnabled()) {
            return;
        }
        checkProfileAllowsMock();
        checkMockKeyPairing();
        log.warn("⚠️ mock 支付渠道已启用（profile={}）—— 此渠道可凭一个 HTTP 请求把订单置为已支付，"
                        + "仅限验收环境使用；生产环境开启会导致启动失败。",
                Arrays.toString(environment.getActiveProfiles()));
    }

    // ==================================================================
    // (1) 始终校验：预下单有效期不得超过订单超时
    // ==================================================================

    private void checkPrepayTtlWithinOrderTimeout() {
        int ttl = payProperties.getPrepayTtlMinutes();
        long orderTimeoutMinutes = VoucherConstants.ORDER_TIMEOUT_DELAY_MS / 60_000L;
        if (ttl > orderTimeoutMinutes) {
            throw new IllegalStateException(String.format(
                    "支付配置非法：hmdp.pay.prepay-ttl-minutes=%d 大于订单超时 %d 分钟。"
                            + "这会产生「支付单还有效、订单已被关」的窗口 —— 用户付了钱而订单已取消，属资损场景。"
                            + "请把 prepay-ttl-minutes 调到 %d 或更小（该值只在 Nacos 生效，改仓库 yaml 无效）。",
                    ttl, orderTimeoutMinutes, orderTimeoutMinutes));
        }
        if (ttl <= 0) {
            throw new IllegalStateException("支付配置非法：hmdp.pay.prepay-ttl-minutes=" + ttl + "，必须为正数（分钟）。");
        }
    }

    // ==================================================================
    // (2) mock 启用时：profile 与密钥
    // ==================================================================

    private void checkProfileAllowsMock() {
        String[] active = environment.getActiveProfiles();
        Set<String> offending = new LinkedHashSet<>();
        for (String profile : active) {
            if (!MOCK_ALLOWED_PROFILES.contains(profile.trim().toLowerCase())) {
                offending.add(profile);
            }
        }
        if (active.length == 0 || !offending.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "拒绝启动：hmdp.pay.mock.enabled=true，但当前 profile=%s 不在允许 mock 的白名单 %s 内。"
                            + "mock 渠道能凭一个 HTTP 请求把任意订单置为已支付，绝不允许在生产环境开启（规格 §3 非目标 7）。"
                            + "若确实要做验收，请显式加 --spring.profiles.active=local（或 dev/test）。",
                    active.length == 0 ? "<未指定>" : Arrays.toString(active), MOCK_ALLOWED_PROFILES));
        }
    }

    /** 校验 mock 私钥与「渠道配置里的验签公钥」是否配对。两份公钥要分别填、格式还不同（支付宝 SDK 收 Base64 无头尾、
     * 微信 SDK 收 PEM），是接入期最容易填错的环节之一；既然私钥能推出公钥，就让它替人检查。 */
    private void checkMockKeyPairing() {
        String privateKeyBase64 = payProperties.getMock().getSignPrivateKey();
        if (isBlank(privateKeyBase64)) {
            throw new IllegalStateException(
                    "拒绝启动：hmdp.pay.mock.enabled=true，但 hmdp.pay.mock.sign-private-key 未配置。"
                            + "没有它就无法给 mock 回调签名，mock 渠道会全程报「渠道未配置」。");
        }

        byte[] expected;
        try {
            expected = derivePublicKeyDer(privateKeyBase64);
        } catch (Exception e) {
            throw new IllegalStateException("hmdp.pay.mock.sign-private-key 解析失败（应为 PKCS#8 Base64，"
                    + "无 PEM 头尾）：" + e.getMessage(), e);
        }

        String alipayPublicKey = payProperties.getAlipay().getAlipayPublicKey();
        if (isBlank(alipayPublicKey)) {
            throw new IllegalStateException(
                    "拒绝启动：mock 渠道已启用，但 hmdp.pay.alipay.alipay-public-key 未配置，"
                            + "支付宝格式的 mock 回调将全部验签失败。请填入 mock 私钥对应的公钥"
                            + "（Base64、无 PEM 头尾）。");
        }
        if (!Arrays.equals(expected, decodeBase64Lenient(alipayPublicKey))) {
            throw new IllegalStateException(
                    "拒绝启动：hmdp.pay.alipay.alipay-public-key 与 hmdp.pay.mock.sign-private-key 不是同一对密钥。"
                            + "这会让 mock 渠道造出的支付宝回调<全部验签失败>，且日志只显示「验签失败」，极难排查。");
        }

        PayProperties.Wechat wechat = payProperties.getWechat();
        if (!wechat.isNotifyConfigured()) {
            throw new IllegalStateException(
                    "拒绝启动：mock 渠道已启用，但微信侧验签配置不齐（需要 hmdp.pay.wechat.api-v3-key / "
                            + "wechat-pay-public-key-id / wechat-pay-public-key），微信格式的 mock 回调无法处理。");
        }
        if (!Arrays.equals(expected, pemToDer(wechat.getWechatPayPublicKey()))) {
            throw new IllegalStateException(
                    "拒绝启动：hmdp.pay.wechat.wechat-pay-public-key 与 hmdp.pay.mock.sign-private-key 不是同一对密钥。"
                            + "微信格式的 mock 回调会全部验签失败。注意微信 SDK 收的是<b>带 PEM 头尾</b>的公钥。");
        }

        log.info("mock 渠道密钥自检通过：私钥与支付宝公钥、微信支付公钥三者配对。");
    }

    /** 由 PKCS#8 私钥推出公钥的 X.509 (DER) 编码。 */
    private static byte[] derivePublicKeyDer(String privateKeyBase64) throws Exception {
        PrivateKey privateKey = MockNotifySigner.loadPrivateKey(privateKeyBase64);
        if (!(privateKey instanceof RSAPrivateCrtKey)) {
            throw new IllegalArgumentException("不是 RSA CRT 私钥，无法推导公钥（实际类型 "
                    + privateKey.getAlgorithm() + "）");
        }
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        return publicKey.getEncoded();
    }

    /** PEM（带 BEGIN/END 头尾）→ DER 字节。微信 SDK 收 PEM，支付宝 SDK 收裸 Base64。 */
    private static byte[] pemToDer(String pem) {
        String base64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return decodeBase64Lenient(base64);
    }

    private static byte[] decodeBase64Lenient(String value) {
        try {
            return Base64.getDecoder().decode(value.replaceAll("\\s", ""));
        } catch (RuntimeException e) {
            return new byte[0];
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
