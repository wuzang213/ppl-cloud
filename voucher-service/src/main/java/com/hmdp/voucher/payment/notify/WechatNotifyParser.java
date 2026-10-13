package com.hmdp.voucher.payment.notify;

import com.hmdp.voucher.payment.WechatTradeState;
import com.hmdp.voucher.payment.config.PayProperties;
import com.hmdp.voucher.payment.domain.PayProvider;
import com.hmdp.voucher.payment.dto.PayNotifyCommand;
import com.wechat.pay.java.core.exception.DecryptionException;
import com.wechat.pay.java.core.exception.MalformedMessageException;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.core.exception.WechatPayException;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.core.notification.RSAPublicKeyNotificationConfig;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 微信支付回调的验签 + 解密 + 解析。职责边界与支付宝侧一致：只做渠道相关判定（验签、解密、字段解析），金额是否与支付单
 * 一致、订单是否还可支付、是否重复回调统一交给 {@link com.hmdp.voucher.payment.service.IPaymentService#applyNotify}。配置只用公钥 +
 * APIv3 密钥、刻意不碰商户私钥（回调验签不需要商户身份），这样「Nacos 里商户私钥路径还没配好」不会连带把回调变成一律拒绝；不使用 {@code RSAAutoCertificateConfig}（构造时会联网下载平台证书，离线环境与纯单测必失败），全程静态公钥模式。留档存原始 body（密文）而非解密后明文：只有原始报文才能离线复验签名，金额与交易号本身已落在 {@code tb_payment.amount / trade_no}。 */
@Component
@Slf4j
public class WechatNotifyParser {

    private final PayProperties payProperties;

    /** 懒初始化：凭据未配时不能让 Bean 构造失败（否则等于服务起不来） */
    private volatile NotificationParser parser;

    public WechatNotifyParser(PayProperties payProperties) {
        this.payProperties = payProperties;
    }

    /** 解析结果，与 {@link AlipayNotifyParser.ParseResult} 同构，便于 Controller 统一处理。
     * @param accepted 是否通过验签与解密 @param rejectReason 未通过时的原因（进日志，便于对账定位）
     * @param command 通过时解析出的命令；未通过时为 {@code null} */
    public record ParseResult(boolean accepted, String rejectReason, PayNotifyCommand command) {

        static ParseResult reject(String reason) {
            return new ParseResult(false, reason, null);
        }
    }

    /** 解析一份微信支付通知。任何一步不过都直接拒绝且不触碰数据库 —— 「签名乱写的报文把订单改成已支付」绝不能发生。
     * {@code NotificationParser#parse} 会一步完成「验签 → 校验 {@code Wechatpay-Serial} → AES-GCM 解密 → 反序列化」，
     * 任一步失败都会抛出，故这里用异常类型区分拒绝原因。@param serial 请求头 {@code Wechatpay-Serial}（用于定位验签公钥，与配置的 publicKeyId 必须一致）@param nonce {@code Wechatpay-Nonce} @param timestamp {@code Wechatpay-Timestamp} @param signature {@code Wechatpay-Signature} @param body 原始请求体 */
    public ParseResult parse(String serial, String nonce, String timestamp, String signature, String body) {
        if (body == null || body.isBlank()) {
            return ParseResult.reject("empty_body");
        }
        PayProperties.Wechat cfg = payProperties.getWechat();
        if (!cfg.isNotifyConfigured()) {
            log.error("微信回调到达，但微信支付公钥/APIv3 密钥未配置，无法验签解密，直接拒绝");
            return ParseResult.reject("wechat_not_configured");
        }
        if (isBlank(serial)) {
            return ParseResult.reject("missing_serial");
        }
        if (isBlank(nonce)) {
            return ParseResult.reject("missing_nonce");
        }
        if (isBlank(timestamp)) {
            return ParseResult.reject("missing_timestamp");
        }
        if (isBlank(signature)) {
            return ParseResult.reject("missing_signature");
        }

        Transaction tx;
        try {
            NotificationConfig notificationConfig = notificationConfig();
            RequestParam param = new RequestParam.Builder()
                    .serialNumber(serial)
                    .nonce(nonce)
                    .timestamp(timestamp)
                    .signature(signature)
                    // signType 是必填项，且必须与验签器注册表的键完全一致。
                    // 这里反读配置里的取值，而不是硬编码字符串 —— 硬编码一旦与 SDK 常量不同，
                    // 报错是「找不到 signType 对应的 verifier」，完全看不出是拼写问题。
                    .signType(notificationConfig.getSignType())
                    .body(body)
                    .build();
            tx = parser(notificationConfig).parse(param, Transaction.class);
        } catch (ValidationException e) {
            // 签名不合法 / Wechatpay-Serial 与配置公钥 ID 不匹配
            log.warn("微信回调校验未通过（签名或 serial）：serial={}, err={}", serial, e.getMessage());
            return ParseResult.reject("validation_failed:" + e.getMessage());
        } catch (DecryptionException e) {
            // 解密失败，最常见原因是 apiV3Key 配错（不是 32 字节或不是同一个密钥）
            log.warn("微信回调解密失败（多半 apiV3Key 配错）：err={}", e.getMessage());
            return ParseResult.reject("decrypt_failed:" + e.getMessage());
        } catch (MalformedMessageException e) {
            log.warn("微信回调报文结构不合法：err={}", e.getMessage());
            return ParseResult.reject("malformed_message:" + e.getMessage());
        } catch (WechatPayException e) {
            log.warn("微信回调处理失败：err={}", e.getMessage());
            return ParseResult.reject("wechat_notify_error:" + e.getMessage());
        }

        if (tx == null) {
            return ParseResult.reject("empty_transaction");
        }
        String outTradeNo = tx.getOutTradeNo();
        if (isBlank(outTradeNo)) {
            return ParseResult.reject("missing_out_trade_no");
        }
        Transaction.TradeStateEnum state = tx.getTradeState();
        if (state == null) {
            return ParseResult.reject("missing_trade_state");
        }
        TransactionAmount txAmount = tx.getAmount();
        Integer total = txAmount == null ? null : txAmount.getTotal();
        if (total == null) {
            // 金额缺失就无从校验一致性。绝不按「未知金额」确认收款。
            return ParseResult.reject("missing_amount");
        }

        String rawStatus = state.name();
        PayNotifyCommand command = new PayNotifyCommand()
                .setProvider(PayProvider.WECHAT)
                .setOutTradeNo(outTradeNo)
                .setTradeNo(tx.getTransactionId())
                // 微信 amount.total 本来就是「分」，与 tb_payment.amount 同口径，直接透传
                .setAmount(total.longValue())
                .setSuccess(WechatTradeState.isPaid(rawStatus))
                .setRawStatus(rawStatus)
                .setPayload(body)
                .setNotifyTime(LocalDateTime.now());
        return new ParseResult(true, null, command);
    }

    private NotificationParser parser(NotificationConfig config) {
        NotificationParser local = parser;
        if (local == null) {
            synchronized (this) {
                local = parser;
                if (local == null) {
                    local = new NotificationParser(config);
                    parser = local;
                }
            }
        }
        return local;
    }

    /** 验签解密只需要「微信支付公钥 + 公钥 ID + APIv3 密钥」，与商户私钥无关。 */
    private NotificationConfig notificationConfig() {
        PayProperties.Wechat cfg = payProperties.getWechat();
        return new RSAPublicKeyNotificationConfig.Builder()
                .publicKey(cfg.getWechatPayPublicKey())
                .publicKeyId(cfg.getWechatPayPublicKeyId())
                .apiV3Key(cfg.getApiV3Key())
                .build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
