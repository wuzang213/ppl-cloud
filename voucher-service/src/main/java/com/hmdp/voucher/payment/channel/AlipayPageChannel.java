package com.hmdp.voucher.payment.channel;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.hmdp.common.exception.BizIllegalException;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.AlipayAmount;
import com.hmdp.voucher.payment.AlipayTradeStatus;
import com.hmdp.voucher.payment.PayChannel;
import com.hmdp.voucher.payment.config.PayProperties;
import com.hmdp.voucher.payment.domain.PayChannelType;
import com.hmdp.voucher.payment.domain.PayQueryResult;
import com.hmdp.voucher.payment.domain.PrepayResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 支付宝电脑网站支付渠道（{@code alipay.trade.page.pay}）。{@code pageExecute} 只做「拼参数 + 加签」、不发任何网络
 * 请求（用户被引导到支付宝站点付款），故「参数与加签接线」可零网络单测钉死。客户端必须懒加载：{@code DefaultAlipayClient}
 * 构造时会解析并校验私钥，凭据为空即抛异常 —— 做成字段初始化或 {@code @PostConstruct} 会把「Nacos 还没填凭据」伪装成服务起不来；懒加载后启动不受影响，真发起支付时才报「渠道未配置」。 */
@Component
@Slf4j
public class AlipayPageChannel implements PayChannel {

    /** 电脑网站支付固定产品码 */
    static final String PRODUCT_CODE = "FAST_INSTANT_TRADE_PAY";

    private final PayProperties payProperties;

    /** 懒初始化的 SDK 客户端（凭据齐备后才可能构建成功） */
    private volatile AlipayClient client;

    public AlipayPageChannel(PayProperties payProperties) {
        this.payProperties = payProperties;
    }

    @Override
    public String code() {
        return PayChannelType.ALIPAY_PAGE.getCode();
    }

    @Override
    public boolean isReady() {
        return payProperties.getAlipay().isConfigured();
    }

    // ==================================================================
    // 预下单
    // ==================================================================

    @Override
    public PrepayResult prepay(Payment payment, String clientIp) {
        // clientIp 用不到：电脑网站支付是「把用户导到支付宝站点」，付款人与本系统无 IP 关联
        PayProperties.Alipay cfg = payProperties.getAlipay();
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setNotifyUrl(cfg.getNotifyUrl());
        request.setReturnUrl(cfg.getReturnUrl());
        request.setBizContent(buildPrepayBizContent(
                payment.getAmount(), payment.getOutTradeNo(), payment.getOrderId(),
                payProperties.getPrepayTtlMinutes()));
        try {
            // "GET" 返回可直接跳转的收银台 URL（POST 会返回一整段自提交表单）
            AlipayTradePagePayResponse response = client().pageExecute(request, "GET");
            String payUrl = response == null ? null : response.getBody();
            if (payUrl == null || payUrl.isBlank()) {
                throw new BizIllegalException("支付宝预下单未返回收银台地址（outTradeNo=" + payment.getOutTradeNo() + "）");
            }
            return PrepayResult.url(code(), payUrl);
        } catch (AlipayApiException e) {
            log.error("支付宝预下单失败：outTradeNo={}, err={}", payment.getOutTradeNo(), e.getMessage());
            throw new BizIllegalException("支付宝预下单失败：" + e.getMessage());
        }
    }

    /** 组装 {@code alipay.trade.page.pay} 的 {@code biz_content}。抽成静态纯函数是为了能被离线单测直接断言 —— 这里是
     * 「分 → 元」换算与 {@code timeout_express} 的最终落点，两个都容易写错且都不报错。
     * @param amountFen 应付金额（分），必须经 {@link AlipayAmount} 换成元（直接把分写进 total_amount 会静默多收 100 倍）@param outTradeNo 商户单号（与支付单一致，回调用它反查）@param orderId 用于生成订单标题 @param timeoutMinutes 支付超时（分钟），必须 ≤ 订单超时（15min），否则出现「支付单还有效、订单已被关」的资损窗口 */
    public static String buildPrepayBizContent(long amountFen, String outTradeNo, Long orderId, int timeoutMinutes) {
        JSONObject biz = JSONUtil.createObj()
                .set("out_trade_no", outTradeNo)
                .set("total_amount", AlipayAmount.fenToYuanString(amountFen))
                .set("subject", "黑马点评团购券-" + orderId)
                .set("product_code", PRODUCT_CODE)
                .set("timeout_express", timeoutMinutes + "m");
        return JSONUtil.toJsonStr(biz);
    }

    // ==================================================================
    // 主动查单
    // ==================================================================

    @Override
    public PayQueryResult query(Payment payment) {
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        request.setBizContent(JSONUtil.toJsonStr(
                JSONUtil.createObj().set("out_trade_no", payment.getOutTradeNo())));
        try {
            AlipayTradeQueryResponse response = client().execute(request);
            if (response == null) {
                return PayQueryResult.pending("NULL_RESPONSE");
            }
            if (!response.isSuccess()) {
                // 常见：ACQ.TRADE_NOT_EXIST（用户还没付款）。按「仍未支付」处理，
                // 让订单超时关单兜底，不在这里擅自改终态。
                return PayQueryResult.pending(response.getSubCode() == null ? response.getCode() : response.getSubCode());
            }
            String status = response.getTradeStatus();
            String tradeNo = response.getTradeNo();
            if (AlipayTradeStatus.isPaid(status)) {
                Long amountFen = AlipayAmount.yuanToFen(response.getTotalAmount());
                if (amountFen == null) {
                    // 渠道说成功了，但无法确认金额 —— 不能凭「未知金额」确认收款。
                    // 返回 PENDING 让下一轮重试并持续报错，等人工介入。
                    log.error("支付宝查单结果金额无法解析，暂不推进：outTradeNo={}, totalAmount={}",
                            payment.getOutTradeNo(), response.getTotalAmount());
                    return PayQueryResult.pending("UNPARSABLE_AMOUNT:" + response.getTotalAmount());
                }
                return PayQueryResult.success(tradeNo, amountFen, status);
            }
            if (AlipayTradeStatus.isClosed(status)) {
                return PayQueryResult.failed(tradeNo, AlipayAmount.yuanToFen(response.getTotalAmount()), status);
            }
            return PayQueryResult.pending(status);
        } catch (AlipayApiException e) {
            log.warn("支付宝查单失败（下轮重试）：outTradeNo={}, err={}", payment.getOutTradeNo(), e.getMessage());
            return PayQueryResult.pending("EXCEPTION:" + e.getMessage());
        }
    }

    private AlipayClient client() {
        AlipayClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    local = buildClient();
                    client = local;
                }
            }
        }
        return local;
    }

    private AlipayClient buildClient() {
        PayProperties.Alipay cfg = payProperties.getAlipay();
        try {
            AlipayConfig config = new AlipayConfig();
            config.setServerUrl(cfg.getGatewayUrl());
            config.setAppId(cfg.getAppId());
            config.setPrivateKey(cfg.getMerchantPrivateKey());
            config.setAlipayPublicKey(cfg.getAlipayPublicKey());
            config.setFormat(cfg.getFormat());
            config.setCharset(cfg.getCharset());
            config.setSignType(cfg.getSignType());
            return new DefaultAlipayClient(config);
        } catch (AlipayApiException e) {
            log.error("构建支付宝客户端失败（凭据格式多半有误）：err={}", e.getMessage());
            throw new BizIllegalException("支付宝凭据配置有误：" + e.getMessage());
        }
    }
}
