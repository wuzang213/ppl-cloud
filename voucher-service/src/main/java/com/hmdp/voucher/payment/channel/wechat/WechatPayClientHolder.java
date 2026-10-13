package com.hmdp.voucher.payment.channel.wechat;

import com.hmdp.common.exception.BizIllegalException;
import com.hmdp.voucher.payment.config.PayProperties;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.service.payments.h5.H5Service;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 微信支付客户端的唯一装配点：Native / H5 两个服务共用一份配置与客户端。必须用静态 {@code RSAPublicKeyConfig} ——
 * 微信 SDK 还有 {@code RSAAutoCertificateConfig}，它在构造时会联网下载平台证书，会让「离线环境 / 纯单测 / 微信接口抖动」
 * 都导致启动或首次调用即失败；本仓库全程用静态公钥模式（{@code wechatPayPublicKeyId} + {@code wechatPayPublicKey}）。懒加载则是因为 {@code RSAPublicKeyConfig.Builder} 会读私钥文件并解析，放在字段初始化或 {@code @PostConstruct} 会把「Nacos 还没填私钥路径」伪装成启动故障。 */
@Component
@Slf4j
public class WechatPayClientHolder {

    private final PayProperties payProperties;

    private volatile RSAPublicKeyConfig config;
    private volatile NativePayService nativePayService;
    private volatile H5Service h5Service;

    public WechatPayClientHolder(PayProperties payProperties) {
        this.payProperties = payProperties;
    }

    /** 共享配置（含商户身份 + 私钥 + 平台公钥）。双重检查加锁：首次调用可能来自并发请求，而构建过程有 IO
     * （读私钥文件），不能每次调用都重建。 */
    public RSAPublicKeyConfig config() {
        RSAPublicKeyConfig local = config;
        if (local == null) {
            synchronized (this) {
                local = config;
                if (local == null) {
                    local = buildConfig();
                    config = local;
                }
            }
        }
        return local;
    }

    public NativePayService nativePayService() {
        NativePayService local = nativePayService;
        if (local == null) {
            synchronized (this) {
                local = nativePayService;
                if (local == null) {
                    local = new NativePayService.Builder().config(config()).build();
                    nativePayService = local;
                }
            }
        }
        return local;
    }

    public H5Service h5Service() {
        H5Service local = h5Service;
        if (local == null) {
            synchronized (this) {
                local = h5Service;
                if (local == null) {
                    local = new H5Service.Builder().config(config()).build();
                    h5Service = local;
                }
            }
        }
        return local;
    }

    private RSAPublicKeyConfig buildConfig() {
        PayProperties.Wechat cfg = payProperties.getWechat();
        try {
            return new RSAPublicKeyConfig.Builder()
                    .merchantId(cfg.getMchId())
                    .merchantSerialNumber(cfg.getMerchantSerialNo())
                    // 私钥只以「路径」形式进配置，文件本身放在仓库外（见 docs/CONFIG-BOUNDARY.md）
                    .privateKeyFromPath(cfg.getMerchantPrivateKeyPath())
                    .apiV3Key(cfg.getApiV3Key())
                    .publicKey(cfg.getWechatPayPublicKey())
                    .publicKeyId(cfg.getWechatPayPublicKeyId())
                    .build();
        } catch (RuntimeException e) {
            // 常见原因：私钥文件路径不存在 / 不是 PKCS#8、apiV3Key 不是 32 字节、公钥不是 PEM
            log.error("装配微信支付客户端失败（多半是凭据格式或路径问题）：err={}", e.getMessage());
            throw new BizIllegalException("微信支付凭据配置有误：" + e.getMessage());
        }
    }
}
