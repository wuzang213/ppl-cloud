package com.hmdp.voucher.payment.config;

import com.hmdp.voucher.constants.VoucherConstants;
import com.hmdp.voucher.payment.support.AlipayNotificationFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付配置启动护栏的单测。
 *
 * <p><b>为什么必须用单测而不是真的启一次服务</b>：计划里 T9 的验证命令是
 * {@code spring-boot:run -Dspring-boot.run.profiles=prod}，但本服务启动要读 Nacos ——
 * 环境不可达时它<b>无论如何都会失败</b>，于是「因为护栏抛错」和「因为拉不到 Nacos」
 * 看起来一模一样，验证就失去了意义。所以把护栏逻辑抽成可单测的纯判定，用
 * {@link MockEnvironment} 精确控制 profile。
 *
 * <p>核心断言只有一条：<b>生产 profile 下开启 mock 必须抛异常</b>（规格 §3 非目标 7）。
 */
class PayMockGuardTest {

    private static final long ORDER_TIMEOUT_MINUTES = VoucherConstants.ORDER_TIMEOUT_DELAY_MS / 60_000L;

    private AlipayNotificationFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new AlipayNotificationFixture();
    }

    // ==================================================================
    // mock 护栏：profile
    // ==================================================================

    @Test
    @DisplayName("prod profile + mock 开启 → 必须拒绝启动，且错误信息说清原因与解法")
    void prodProfileWithMockEnabledRefusesToStart() {
        PayProperties props = pairedProperties();
        props.getMock().setEnabled(true);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PayMockGuard(env("prod"), props).afterPropertiesSet());

        assertTrue(e.getMessage().contains("拒绝启动"), e.getMessage());
        assertTrue(e.getMessage().contains("prod"), e.getMessage());
        assertTrue(e.getMessage().contains("mock"), e.getMessage());
    }

    @Test
    @DisplayName("未指定任何 profile + mock 开启 → 同样拒绝（「没声明是开发环境」不等于开发环境）")
    void emptyProfileWithMockEnabledRefusesToStart() {
        PayProperties props = pairedProperties();
        props.getMock().setEnabled(true);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PayMockGuard(new MockEnvironment(), props).afterPropertiesSet());

        assertTrue(e.getMessage().contains("未指定"), e.getMessage());
    }

    @Test
    @DisplayName("开发 profile（local / dev / test）+ mock 开启 + 密钥配对 → 放行")
    void devProfilesWithPairedKeysAreAllowed() {
        for (String profile : new String[]{"local", "dev", "test"}) {
            PayProperties props = pairedProperties();
            props.getMock().setEnabled(true);
            assertDoesNotThrow(() -> new PayMockGuard(env(profile), props).afterPropertiesSet(),
                    "profile=" + profile + " 应被放行");
        }
    }

    @Test
    @DisplayName("prod profile + mock 关闭 → 放行（关着的 mock 与生产无关）")
    void prodProfileWithMockDisabledIsAllowed() {
        PayProperties props = pairedProperties();
        props.getMock().setEnabled(false);
        assertDoesNotThrow(() -> new PayMockGuard(env("prod"), props).afterPropertiesSet());
    }

    // ==================================================================
    // mock 护栏：密钥配对
    // ==================================================================

    @Test
    @DisplayName("mock 开启但未配签名私钥 → 拒绝启动（否则 mock 会全程报「渠道未配置」）")
    void mockWithoutSigningKeyRefusesToStart() {
        PayProperties props = pairedProperties();
        props.getMock().setEnabled(true);
        props.getMock().setSignPrivateKey(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PayMockGuard(env("local"), props).afterPropertiesSet());
        assertTrue(e.getMessage().contains("sign-private-key"), e.getMessage());
    }

    @Test
    @DisplayName("支付宝公钥与 mock 私钥不配对 → 拒绝启动（否则症状是「回调全部验签失败」）")
    void mismatchedAlipayPublicKeyRefusesToStart() throws Exception {
        PayProperties props = pairedProperties();
        props.getMock().setEnabled(true);
        props.getAlipay().setAlipayPublicKey(fixture.unrelatedPublicKey());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PayMockGuard(env("local"), props).afterPropertiesSet());
        assertTrue(e.getMessage().contains("alipay-public-key"), e.getMessage());
    }

    @Test
    @DisplayName("微信支付公钥与 mock 私钥不配对 → 拒绝启动（PEM 与 Base64 两种格式都要能认）")
    void mismatchedWechatPublicKeyRefusesToStart() throws Exception {
        PayProperties props = pairedProperties();
        props.getMock().setEnabled(true);
        props.getWechat().setWechatPayPublicKey(toPem(fixture.unrelatedPublicKey()));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PayMockGuard(env("local"), props).afterPropertiesSet());
        assertTrue(e.getMessage().contains("wechat-pay-public-key"), e.getMessage());
    }

    @Test
    @DisplayName("mock 开启但微信验签配置不全 → 拒绝启动")
    void mockWithoutWechatNotifyConfigRefusesToStart() {
        PayProperties props = pairedProperties();
        props.getMock().setEnabled(true);
        props.getWechat().setApiV3Key(null);

        assertThrows(IllegalStateException.class,
                () -> new PayMockGuard(env("local"), props).afterPropertiesSet());
    }

    // ==================================================================
    // 始终生效：预下单有效期 ≤ 订单超时
    // ==================================================================

    @Test
    @DisplayName("默认 5 分钟 ≤ 订单超时 15 分钟 → 放行（这是资损窗口的硬约束）")
    void defaultPrepayTtlIsWithinOrderTimeout() {
        PayProperties props = pairedProperties();
        assertEquals(5, props.getPrepayTtlMinutes());
        assertTrue(props.getPrepayTtlMinutes() <= ORDER_TIMEOUT_MINUTES);
        assertDoesNotThrow(() -> new PayMockGuard(env("prod"), props).afterPropertiesSet());
    }

    @Test
    @DisplayName("prepay-ttl 超过订单超时 → 拒绝启动（mock 关着也要拦：它与 mock 无关）")
    void prepayTtlBeyondOrderTimeoutRefusesToStart() {
        PayProperties props = pairedProperties();
        props.setPrepayTtlMinutes((int) ORDER_TIMEOUT_MINUTES + 5);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PayMockGuard(env("prod"), props).afterPropertiesSet());
        assertTrue(e.getMessage().contains("prepay-ttl-minutes"), e.getMessage());
        assertTrue(e.getMessage().contains("资损"), e.getMessage());
    }

    @Test
    @DisplayName("prepay-ttl 为 0 或负数 → 拒绝启动")
    void nonPositivePrepayTtlRefusesToStart() {
        for (int ttl : new int[]{0, -1}) {
            PayProperties props = pairedProperties();
            props.setPrepayTtlMinutes(ttl);
            assertThrows(IllegalStateException.class,
                    () -> new PayMockGuard(env("prod"), props).afterPropertiesSet(),
                    "ttl=" + ttl + " 应被拒绝");
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        if (profiles.length > 0) {
            environment.setActiveProfiles(profiles);
        }
        return environment;
    }

    /** 一套「mock 私钥 ↔ 支付宝公钥 ↔ 微信支付公钥」三者配对的配置。 */
    private PayProperties pairedProperties() {
        PayProperties p = fixture.properties();
        p.getMock().setSignPrivateKey(fixture.merchantPrivateKey);
        PayProperties.Wechat w = p.getWechat();
        w.setApiV3Key("hmdp0000hmdp0000hmdp0000hmdp0000");
        w.setWechatPayPublicKeyId("PUB_KEY_ID_0001");
        // 同一对密钥的 PEM 表示（微信 SDK 收 PEM，支付宝 SDK 收裸 Base64）
        w.setWechatPayPublicKey(toPem(fixture.alipayPublicKey));
        return p;
    }

    private static String toPem(String base64Der) {
        StringBuilder sb = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < base64Der.length(); i += 64) {
            sb.append(base64Der, i, Math.min(i + 64, base64Der.length())).append('\n');
        }
        return sb.append("-----END PUBLIC KEY-----\n").toString();
    }
}
