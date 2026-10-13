package com.hmdp.voucher.payment;

import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.channel.AlipayPageChannel;
import com.hmdp.voucher.payment.channel.wechat.WechatPayClientHolder;
import com.hmdp.voucher.payment.config.PayProperties;
import com.hmdp.voucher.payment.domain.PrepayResult;
import com.hmdp.voucher.payment.support.AlipayNotificationFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两个支付 SDK 在同一 ClassLoader 下的<b>共存冒烟</b>（零网络）。
 *
 * <p><b>为什么专门为「共存」写一个测试</b>：支付宝 SDK 与微信 SDK 依赖同一个 okhttp，
 * 但声明的版本不同（支付宝声明 3.12.13，微信需要 4.x 才有的
 * {@code RequestBody.create(String, MediaType)}）。一旦依赖解析选到了 3.x，
 * 症状是「构造都正常、第一次发请求才 {@code NoSuchMethodError}」——
 * 在真机上就是「支付偶发失败」，且只在微信侧出现，极难定位。本测试把这件事提前到构建期。
 *
 * <p><b>为什么用反射调 okhttp 而不是直接 new 一个请求发出去</b>：发请求就不是零网络了。
 * 反射调用 {@code RequestBody.create(String, MediaType)} 精确命中那一个风险点，
 * 而且能顺带证明它<b>不只是存在、还能用</b>（长度正确、contentType 正确）。
 *
 * <p>微信侧的「装配」也刻意停在构造：{@code NativePayService} 构造只做本地配置校验，
 * 不发任何请求；而 prepay 必然联网，本轮无凭据无公网，只能留到 L2。
 */
class PaySdkCoexistenceTest {

    private static final String API_V3_KEY = "hmdp0000hmdp0000hmdp0000hmdp0000";
    private static final String MCH_ID = "1900000001";
    private static final String APP_ID = "wx8888888888888888";
    private static final String PUBLIC_KEY_ID = "PUB_KEY_ID_0001";
    private static final String SERIAL_NO = "444F4861A2B3C4D5E6F708192A3B4C5D6E7F8091";

    // ==================================================================
    // 1. okhttp 版本（微信 SDK 的硬依赖）
    // ==================================================================

    @Test
    @DisplayName("类路径上的 okhttp 必须是 4.x：微信依赖的 RequestBody.create(String, MediaType) 存在且可调用")
    void okhttpMustCarryWechatRequiredFactory() throws Exception {
        Class<?> requestBody = Class.forName("okhttp3.RequestBody");
        Class<?> mediaType = Class.forName("okhttp3.MediaType");

        // 4.x 才有的静态工厂 —— 若依赖树解析到 3.12.13，这一行就抛 NoSuchMethodException
        Method createFromString = requestBody.getMethod("create", String.class, mediaType);
        assertTrue(Modifier.isStatic(createFromString.getModifiers()),
                "okhttp 4.x 的 create(String, MediaType) 应为静态方法");

        // 3.x 的旧签名在 4.x 中仍保留（deprecated），两者同时存在是「4.x」的指纹
        Method legacy = requestBody.getMethod("create", mediaType, String.class);
        assertTrue(Modifier.isStatic(legacy.getModifiers()));

        // 不只是存在：真的调用一次，确认可调用且行为正确
        Object json = mediaType.getMethod("get", String.class).invoke(null, "application/json");
        assertNotNull(json, "MediaType.get 应返回实例");
        String content = "{\"a\":1}";
        Object body = createFromString.invoke(null, content, json);
        assertNotNull(body);

        long length = ((Number) requestBody.getMethod("contentLength").invoke(body)).longValue();
        assertEquals(content.length(), length, "RequestBody 的长度应为内容字节数");
        Object contentType = requestBody.getMethod("contentType").invoke(body);
        assertNotNull(contentType);
        assertTrue(contentType.toString().startsWith("application/json"),
                "contentType 应保留传入值，实际：" + contentType);
    }

    // ==================================================================
    // 2. 支付宝：零网络产出收银台 URL
    // ==================================================================

    @Test
    @DisplayName("支付宝电脑网站支付：零网络产出收银台 URL，金额以「元」两位小数承载")
    void alipayPagePayWorksOffline() throws Exception {
        AlipayNotificationFixture fixture = new AlipayNotificationFixture();
        AlipayPageChannel channel = new AlipayPageChannel(fixture.properties());

        Payment payment = new Payment();
        payment.setOrderId(20001L);
        payment.setOutTradeNo("PAY1001");
        payment.setAmount(1000L);
        payment.setChannel("alipay_page");

        // pageExecute 只做「拼参数 + 加签」，不发网络请求 —— 因此这一步在离线是可信的
        PrepayResult result = channel.prepay(payment, null);

        assertEquals("url", result.getKind(), "支付宝返回的是可直接跳转的 URL");
        assertEquals("alipay_page", result.getChannel());

        // 收银台 URL 的 biz_content 整体被 URL 编码，必须先解码再断言
        String decoded = URLDecoder.decode(result.getPayload(), StandardCharsets.UTF_8.name());
        assertTrue(decoded.contains("\"total_amount\":\"10.00\""),
                "1000 分必须换成 10.00 元（直接把分写进去会静默多收 100 倍）：" + decoded);
        assertTrue(decoded.contains("\"out_trade_no\":\"PAY1001\""), decoded);
        assertTrue(decoded.contains("FAST_INSTANT_TRADE_PAY"), decoded);
    }

    // ==================================================================
    // 3. 微信：零网络装配
    // ==================================================================

    @Test
    @DisplayName("微信 Native/H5 服务：零网络装配成功（静态公钥模式，构造时不下载证书）")
    void wechatServicesAssembleOffline(@TempDir Path tempDir) throws Exception {
        WechatPayClientHolder holder = new WechatPayClientHolder(wechatProperties(tempDir));

        assertNotNull(holder.config(), "静态公钥配置应能装配（不含任何联网动作）");
        assertNotNull(holder.nativePayService());
        assertNotNull(holder.h5Service());

        // 懒加载 + 双重检查加锁：重复取必须拿到同一实例，否则每次请求都会重建客户端
        assertSame(holder.nativePayService(), holder.nativePayService());
        assertSame(holder.h5Service(), holder.h5Service());
        assertSame(holder.config(), holder.config());
    }

    // ==================================================================
    // 4. 共存：同一次流程里两个 SDK 都用一遍
    // ==================================================================

    @Test
    @DisplayName("共存冒烟：同一 ClassLoader 下先跑支付宝加签、再装配微信客户端，两者互不影响")
    void bothSdksWorkInTheSameFlow(@TempDir Path tempDir) throws Exception {
        // ① 支付宝：加载 SDK 类 + 用 RSA2 加签（这一步会真正用到支付宝 SDK 的密码学实现）
        AlipayNotificationFixture alipayFixture = new AlipayNotificationFixture();
        AlipayPageChannel alipayChannel = new AlipayPageChannel(alipayFixture.properties());
        Payment payment = new Payment();
        payment.setOrderId(20002L);
        payment.setOutTradeNo("PAY1002");
        payment.setAmount(500L);
        payment.setChannel("alipay_page");
        assertNotNull(alipayChannel.prepay(payment, null).getPayload());

        // ② 微信：同一线程紧接着装配，确认没有被前一步的类初始化影响
        WechatPayClientHolder wechatHolder = new WechatPayClientHolder(wechatProperties(tempDir));
        assertNotNull(wechatHolder.nativePayService());
        assertNotNull(wechatHolder.h5Service());

        // ③ 再回头用一次支付宝：确认微信 SDK 的加载没有污染 okhttp / 签名实现
        assertNotNull(alipayChannel.prepay(payment, null).getPayload());

        // ④ 最后再碰一次那唯一共享的底层依赖
        Class<?> requestBody = Class.forName("okhttp3.RequestBody");
        assertNotNull(requestBody.getMethod("create", String.class, Class.forName("okhttp3.MediaType")));
    }

    // ==================================================================
    // 装配辅助
    // ==================================================================

    private static PayProperties wechatProperties(Path dir) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        // 微信 SDK 的 privateKeyFromPath 要求真实存在的 PKCS#8 PEM 文件
        Path keyFile = dir.resolve("apiclient_key.pem");
        Files.writeString(keyFile, pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));

        PayProperties p = new PayProperties();
        PayProperties.Wechat w = p.getWechat();
        w.setAppId(APP_ID);
        w.setMchId(MCH_ID);
        w.setMerchantSerialNo(SERIAL_NO);
        w.setMerchantPrivateKeyPath(keyFile.toString());
        w.setApiV3Key(API_V3_KEY);
        w.setWechatPayPublicKeyId(PUBLIC_KEY_ID);
        w.setWechatPayPublicKey(pem("PUBLIC KEY", keyPair.getPublic().getEncoded()));
        w.setNotifyUrl("https://example.com/voucher-order/pay/notify/wechat");
        return p;
    }

    private static String pem(String label, byte[] der) {
        String base64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        return sb.append("-----END ").append(label).append("-----\n").toString();
    }
}
