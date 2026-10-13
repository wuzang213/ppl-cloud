package com.hmdp.voucher.payment.channel.wechat;

import com.hmdp.common.exception.BizIllegalException;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.PayChannel;
import com.hmdp.voucher.payment.config.PayProperties;
import com.hmdp.voucher.payment.domain.PayChannelType;
import com.hmdp.voucher.payment.domain.PayQueryResult;
import com.hmdp.voucher.payment.domain.PrepayResult;
import com.wechat.pay.java.core.exception.WechatPayException;
import com.wechat.pay.java.service.payments.h5.model.Amount;
import com.wechat.pay.java.service.payments.h5.model.H5Info;
import com.wechat.pay.java.service.payments.h5.model.PrepayRequest;
import com.wechat.pay.java.service.payments.h5.model.PrepayResponse;
import com.wechat.pay.java.service.payments.h5.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.h5.model.SceneInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 微信 H5 支付渠道（{@code /v3/pay/transactions/h5}，手机浏览器内跳转）。与 Native 的关键差异：必须带用户端 IP ——
 * 微信把 {@code scene_info.payer_client_ip} 定为必填（用于风控定位），拿不到就不可能发出单，故 {@link PayChannel#prepay(Payment)}
 * 那个不带 IP 的重载走到这里会直接报错，而不是发一个注定被拒的请求。SDK 的 {@code prepay} 会真发 HTTP，离线单测只能覆盖 {@link #buildPrepayRequest} 这个纯函数。 */
@Component
@Slf4j
public class WechatH5Channel implements PayChannel {

    /** 网页版 H5 支付的场景类型，微信侧取值固定为 {@code Wap} */
    static final String H5_SCENE_TYPE = "Wap";

    private final PayProperties payProperties;
    private final WechatPayClientHolder clientHolder;

    public WechatH5Channel(PayProperties payProperties, WechatPayClientHolder clientHolder) {
        this.payProperties = payProperties;
        this.clientHolder = clientHolder;
    }

    @Override
    public String code() {
        return PayChannelType.WECHAT_H5.getCode();
    }

    @Override
    public boolean isReady() {
        return payProperties.getWechat().isConfigured();
    }

    @Override
    public PrepayResult prepay(Payment payment, String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            throw new BizIllegalException("微信 H5 支付缺少用户端 IP（scene_info.payer_client_ip 为必填），"
                    + "无法发起支付（outTradeNo=" + payment.getOutTradeNo() + "）");
        }
        PayProperties.Wechat cfg = payProperties.getWechat();
        PrepayRequest request = buildPrepayRequest(cfg.getAppId(), cfg.getMchId(), cfg.getNotifyUrl(),
                payment.getOutTradeNo(), payment.getOrderId(), payment.getAmount(),
                payProperties.getPrepayTtlMinutes(), clientIp);
        try {
            PrepayResponse response = clientHolder.h5Service().prepay(request);
            if (response == null || response.getH5Url() == null || response.getH5Url().isBlank()) {
                throw new BizIllegalException("微信 H5 预下单未返回 h5_url"
                        + "（outTradeNo=" + payment.getOutTradeNo() + "）");
            }
            return PrepayResult.h5Url(code(), response.getH5Url());
        } catch (WechatPayException e) {
            log.error("微信 H5 预下单失败：outTradeNo={}, err={}", payment.getOutTradeNo(), e.getMessage());
            throw new BizIllegalException("微信 H5 预下单失败：" + e.getMessage());
        }
    }

    /** 组装 H5 预下单请求（纯函数，便于离线单测）。
     * @param amountFen 应付金额（分），微信直接收分、不换算 @param ttlMinutes 支付超时（分钟）
     * @param clientIp 用户端 IP，必填（由 Controller 从请求来源取，本方法不再兜底猜测） */
    public static PrepayRequest buildPrepayRequest(String appId, String mchId, String notifyUrl,
                                                   String outTradeNo, Long orderId, long amountFen,
                                                   int ttlMinutes, String clientIp) {
        Amount amount = new Amount();
        amount.setTotal(WechatPrepay.toTotal(amountFen));
        amount.setCurrency("CNY");

        H5Info h5Info = new H5Info();
        h5Info.setType(H5_SCENE_TYPE);

        SceneInfo sceneInfo = new SceneInfo();
        sceneInfo.setPayerClientIp(clientIp);
        sceneInfo.setH5Info(h5Info);

        PrepayRequest request = new PrepayRequest();
        request.setAppid(appId);
        request.setMchid(mchId);
        request.setDescription("黑马点评团购券-" + orderId);
        request.setOutTradeNo(outTradeNo);
        request.setNotifyUrl(notifyUrl);
        request.setTimeExpire(WechatPrepay.timeExpire(ttlMinutes));
        request.setAmount(amount);
        request.setSceneInfo(sceneInfo);
        return request;
    }

    @Override
    public PayQueryResult query(Payment payment) {
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setOutTradeNo(payment.getOutTradeNo());
        request.setMchid(payProperties.getWechat().getMchId());
        try {
            return WechatQueryResults.of(
                    clientHolder.h5Service().queryOrderByOutTradeNo(request), payment.getOutTradeNo());
        } catch (WechatPayException e) {
            log.warn("微信 H5 查单失败（下轮重试）：outTradeNo={}, err={}",
                    payment.getOutTradeNo(), e.getMessage());
            return PayQueryResult.pending("EXCEPTION:" + e.getMessage());
        }
    }
}
