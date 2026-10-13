package com.hmdp.voucher.payment.config;

import com.hmdp.voucher.payment.domain.PayChannelType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 支付配置（Nacos: {@code voucher-service.yaml}，前缀 {@code hmdp.pay}）。⚠️ 改这里通常无效：运行期配置真相在 Nacos，
 * 仓库内的 yaml 只是骨架（见 {@code docs/CONFIG-BOUNDARY.md}），真实凭据一律只写 Nacos、绝不进仓库。本类全部字段都有
 * 默认值 —— 缺少整个 {@code hmdp.pay} 段时服务必须仍能启动（否则「Nacos 还没配」会表现成「服务起不来」）；凭据缺失由各渠道的 {@link com.hmdp.voucher.payment.PayChannel#isReady()} 在调用时报错，而不是启动时。 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.pay")
public class PayProperties {

    /** {@link #queryTaskIntervalMillis} 的默认值。提成常量是为了让 {@code PayQueryTask} 的 {@code @Scheduled} 占位符能引用它 ——
     * 注解里写死一个字面量、字段里再写一个，两处迟早会漂移（表现为「配了 2 分钟却按别的间隔跑」）。 */
    public static final long DEFAULT_QUERY_INTERVAL_MILLIS = 120_000L;

    /**
     * 请求体不带 channel 时使用的默认渠道。
     */
    private String defaultChannel = PayChannelType.ALIPAY_PAGE.getCode();

    /** 预下单有效期（分钟）。必须 ≤ 订单超时（15min），否则会出现「支付单还有效、订单已被关」的窗口 ——
     * 用户付了钱但订单已取消，属资损场景。 */
    private int prepayTtlMinutes = 5;

    /** 主动查单任务的扫描间隔（毫秒）。默认 2 分钟：太频繁会撞渠道侧的查单限流（支付宝对同一订单的查询有频率限制）。 */
    private long queryTaskIntervalMillis = DEFAULT_QUERY_INTERVAL_MILLIS;

    /** 是否信任 {@code X-Forwarded-For} 来确定用户端 IP（用于微信 H5 的 {@code scene_info.payer_client_ip}）。默认 true：
     * 本仓库是「网关在最前面」，voucher-service 收到的 TCP 源地址永远是网关的，取 {@code getRemoteAddr()} 会把网关 IP 当
     * 用户 IP 报给微信（微信不报错、只拿它做风控定位，属静默填错）。若把本服务直接暴露（不经网关），该头可被客户端伪造 —— 不构成资损（钱只流向订单所属账号），但仍建议关掉。取值口径见 {@code PaymentClientIpResolver} 与网关的 {@code hmdp.gateway.rate-limit.trust-x-forwarded-for}，两者是独立开关。 */
    private boolean trustXForwardedFor = true;

    private Alipay alipay = new Alipay();

    private Wechat wechat = new Wechat();

    private Mock mock = new Mock();

    @Data
    public static class Alipay {

        /** 应用 appId */
        private String appId;

        /** 应用私钥（PKCS#8，Base64，无 PEM 头尾）—— 用于签名 */
        private String merchantPrivateKey;

        /** 支付宝公钥（用于验签回调）。注意：不是应用公钥 */
        private String alipayPublicKey;

        private String gatewayUrl = "https://openapi.alipay.com/gateway.do";

        /** 服务器异步通知地址，必须公网可达 https */
        private String notifyUrl;

        /** 同步跳转地址（用户付款后浏览器跳回） */
        private String returnUrl;

        private String signType = "RSA2";

        private String charset = "UTF-8";

        private String format = "json";

        /** 能否<b>发起支付</b>（预下单 / 生成收银台 URL）：需要应用身份 + 应用私钥 + 支付宝公钥 */
        public boolean isConfigured() {
            return hasText(appId) && hasText(merchantPrivateKey) && hasText(alipayPublicKey);
        }

        /** 能否验签回调。刻意比 {@link #isConfigured()} 弱：验签只需要「支付宝公钥」+ 用于比对 {@code app_id} 的
         * {@code appId}，不需要应用私钥（私钥只在发单时给请求签名，回调由支付宝签名，用的是另一半）。与
         * {@link Wechat#isNotifyConfigured()} 对称：回调是资损关键路径，「应用私钥还没配好」不该连带把回调验签变成一律拒绝 —— 那种失败是静默的（端点回 200 + 支付单永远停在 PENDING），比直接报「配置缺失」难查得多。 */
        public boolean isNotifyConfigured() {
            return hasText(appId) && hasText(alipayPublicKey);
        }
    }

    @Data
    public static class Wechat {

        /** 公众号 / 应用 appId。Native 与 H5 预下单都必须带（微信侧用它确定收款主体），缺了会在调渠道时被拒 ——
         * 因此它属于「能否发起支付」的必需项，不是可选项。 */
        private String appId;

        /** 商户号 */
        private String mchId;

        /** APIv3 密钥（32 字节），用于解密回调的 resource */
        private String apiV3Key;

        /** 商户证书序列号 */
        private String merchantSerialNo;

        /**
         * 商户私钥文件路径。⚠️ 文件放<b>仓库外</b>，只把路径写进 Nacos。
         */
        private String merchantPrivateKeyPath;

        /** 微信支付公钥 ID（报头 Wechatpay-Serial 要与之匹配） */
        private String wechatPayPublicKeyId;

        /** 微信支付公钥内容（验证回调签名） */
        private String wechatPayPublicKey;

        /** 服务器异步通知地址，必须公网可达 https */
        private String notifyUrl;

        /** 能否<b>发起支付</b>（预下单）：需要商户身份 + 私钥 + 平台公钥 */
        public boolean isConfigured() {
            return hasText(appId) && hasText(mchId) && hasText(apiV3Key) && hasText(merchantSerialNo)
                    && hasText(merchantPrivateKeyPath) && isNotifyConfigured();
        }

        /** 能否验签解密回调。刻意比 {@link #isConfigured()} 弱：回调验签只需「微信支付公钥 + 公钥 ID + APIv3 密钥」，
         * 不需要商户私钥；分开判定是因为回调是资损关键路径 —— 「Nacos 里商户私钥路径还没填好」不该连带把回调验签变成一律拒绝。 */
        public boolean isNotifyConfigured() {
            return hasText(apiV3Key) && hasText(wechatPayPublicKeyId) && hasText(wechatPayPublicKey);
        }
    }

    /** 验收用 mock 渠道的配置。不是生产配置：生产环境 {@code enabled=true} 会导致启动失败（fail fast），见 {@code PayMockGuard}。 */
    @Data
    public static class Mock {

        /** 默认关闭。开启后才会装配 MockPayChannel 及其触发端点。 */
        private boolean enabled = false;

        /** 验收期临时 RSA 私钥（PKCS#8，Base64，无 PEM 头尾），仅用于给 mock 回调签名。它对应的公钥要以两种格式分别填到
         * 渠道配置（两个 SDK 各自要求，绕不开）：{@code alipay.alipay-public-key}（Base64、无 PEM 头尾，支付宝 SDK 格式）与
         * {@code wechat.wechat-pay-public-key}（带 BEGIN/END PEM 头尾，微信 SDK 格式）；三者是否配对由 {@code PayMockGuard} 启动时校验（填错直接拒绝启动，而非等到验收时表现成「所有回调都验签失败」）。验收完成后删除该配置项与 Nacos 里的取值。 */
        private String signPrivateKey;

        /** mock 收银台与回调投递的基址（例如 {@code http://your-host:8084}）。留空则退化为
         * {@code http://127.0.0.1:{server.port}} —— 本机验收够用；若服务跑在远端主机而人从开发机点收银台，需显式配成该主机的地址。 */
        private String baseUrl;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
