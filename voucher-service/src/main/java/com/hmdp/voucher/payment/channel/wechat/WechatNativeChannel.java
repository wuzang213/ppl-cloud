package com.hmdp.voucher.payment.channel.wechat;

import com.hmdp.common.exception.BizIllegalException;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.PayChannel;
import com.hmdp.voucher.payment.config.PayProperties;
import com.hmdp.voucher.payment.domain.PayChannelType;
import com.hmdp.voucher.payment.domain.PayQueryResult;
import com.hmdp.voucher.payment.domain.PrepayResult;
import com.wechat.pay.java.core.exception.WechatPayException;
import com.wechat.pay.java.service.payments.nativepay.model.Amount;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayResponse;
import com.wechat.pay.java.service.payments.nativepay.model.QueryOrderByOutTradeNoRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 微信 Native 扫码支付渠道（{@code /v3/pay/transactions/native}）。与支付宝的最大区别：SDK 的 {@code prepay} 会向微信
 * 真的发一次 HTTP 换取 {@code code_url}（渠道协议如此、绕不开），故零网络单测只能覆盖 {@link #buildPrepayRequest}
 * 这个纯函数（金额有没有漏换算、必需字段有没有带齐），发单本身必须等商户号就绪才能验。 */
@Component
@Slf4j
public class WechatNativeChannel implements PayChannel {

    private final PayProperties payProperties;
    private final WechatPayClientHolder clientHolder;

    public WechatNativeChannel(PayProperties payProperties, WechatPayClientHolder clientHolder) {
        this.payProperties = payProperties;
        this.clientHolder = clientHolder;
    }

    @Override
    public String code() {
        return PayChannelType.WECHAT_NATIVE.getCode();
    }

    @Override
    public boolean isReady() {
        return payProperties.getWechat().isConfigured();
    }

    @Override
    public PrepayResult prepay(Payment payment, String clientIp) {
        // clientIp 用不到：扫码支付是用户用微信 App 主动扫，与浏览器来源 IP 无关
        PayProperties.Wechat cfg = payProperties.getWechat();
        PrepayRequest request = buildPrepayRequest(cfg.getAppId(), cfg.getMchId(), cfg.getNotifyUrl(),
                payment.getOutTradeNo(), payment.getOrderId(), payment.getAmount(),
                payProperties.getPrepayTtlMinutes());
        try {
            PrepayResponse response = clientHolder.nativePayService().prepay(request);
            if (response == null || response.getCodeUrl() == null || response.getCodeUrl().isBlank()) {
                throw new BizIllegalException("微信 Native 预下单未返回 code_url"
                        + "（outTradeNo=" + payment.getOutTradeNo() + "）");
            }
            return PrepayResult.codeUrl(code(), response.getCodeUrl());
        } catch (WechatPayException e) {
            log.error("微信 Native 预下单失败：outTradeNo={}, err={}", payment.getOutTradeNo(), e.getMessage());
            throw new BizIllegalException("微信 Native 预下单失败：" + e.getMessage());
        }
    }

    /** 组装 Native 预下单请求（纯函数，便于离线单测）。
     * @param amountFen 应付金额（分），微信直接收分、不做换算，仅经 {@link WechatPrepay#toTotal} 做 int 边界校验
     * @param ttlMinutes 支付超时（分钟），落成 {@code time_expire}（绝对时刻） */
    public static PrepayRequest buildPrepayRequest(String appId, String mchId, String notifyUrl,
                                                   String outTradeNo, Long orderId, long amountFen,
                                                   int ttlMinutes) {
        Amount amount = new Amount();
        amount.setTotal(WechatPrepay.toTotal(amountFen));
        amount.setCurrency("CNY");

        PrepayRequest request = new PrepayRequest();
        request.setAppid(appId);
        request.setMchid(mchId);
        request.setDescription("黑马点评团购券-" + orderId);
        request.setOutTradeNo(outTradeNo);
        request.setNotifyUrl(notifyUrl);
        request.setTimeExpire(WechatPrepay.timeExpire(ttlMinutes));
        request.setAmount(amount);
        return request;
    }

    @Override
    public PayQueryResult query(Payment payment) {
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setOutTradeNo(payment.getOutTradeNo());
        request.setMchid(payProperties.getWechat().getMchId());
        try {
            return WechatQueryResults.of(
                    clientHolder.nativePayService().queryOrderByOutTradeNo(request), payment.getOutTradeNo());
        } catch (WechatPayException e) {
            // 查单失败（网络/渠道报错）→ 交回「仍未支付」，由下一轮重试或订单超时关单兜底。
            // 绝不在这里擅自推进状态。
            log.warn("微信 Native 查单失败（下轮重试）：outTradeNo={}, err={}",
                    payment.getOutTradeNo(), e.getMessage());
            return PayQueryResult.pending("EXCEPTION:" + e.getMessage());
        }
    }
}
