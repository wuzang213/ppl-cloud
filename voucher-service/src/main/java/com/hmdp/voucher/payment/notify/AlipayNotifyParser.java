package com.hmdp.voucher.payment.notify;

import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import com.hmdp.voucher.payment.AlipayAmount;
import com.hmdp.voucher.payment.AlipayTradeStatus;
import com.hmdp.voucher.payment.config.PayProperties;
import com.hmdp.voucher.payment.domain.PayProvider;
import com.hmdp.voucher.payment.dto.PayNotifyCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 支付宝异步通知的解析与验签。职责边界：本类只做渠道相关判定（验签、归属 app_id、字段解析）；金额是否与支付单一致、
 * 订单是否还可支付、是否重复回调属业务规则，统一交给 {@link com.hmdp.voucher.payment.service.IPaymentService#applyNotify}，这样
 * 微信侧只需换一个解析器、业务规则完全复用。验签必须用支付宝公钥，不是应用公钥 —— 这是接入期最常见的错配，表现为「所有回调都验签失败」，而日志里看不出区别。 */
@Component
@Slf4j
public class AlipayNotifyParser {

    private final PayProperties payProperties;

    public AlipayNotifyParser(PayProperties payProperties) {
        this.payProperties = payProperties;
    }

    /** 解析结果。
     * @param accepted 是否通过验签与必需项校验 @param rejectReason 未通过时的原因（会出现在应答里，便于对账定位）
     * @param command 通过时解析出的命令；未通过时为 {@code null} */
    public record ParseResult(boolean accepted, String rejectReason, PayNotifyCommand command) {

        static ParseResult reject(String reason) {
            return new ParseResult(false, reason, null);
        }
    }

    /** 解析一份支付宝异步通知表单。校验顺序刻意从「最便宜且最能挡住伪造」的开始：先验签，再查 app_id，最后才是字段解析；
     * 任何一步不过都直接拒绝且不触碰数据库 —— 「签名乱写的报文把订单改成已支付」绝对不能发生。 */
    public ParseResult parse(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return ParseResult.reject("empty_params");
        }
        PayProperties.Alipay cfg = payProperties.getAlipay();
        if (!cfg.isNotifyConfigured()) {
            // 没配公钥就没法验签，必须拒绝，绝不能「先放过去」。
            // ⚠️ 必须用 isNotifyConfigured（appId + 支付宝公钥），不能用 isConfigured（后者还要求发单用的应用私钥）：
            // 那样私钥没配会让回调全部被拒，且失败是**静默的**（端点回 200 但支付单永远停在 PENDING）。
            log.error("支付宝回调到达，但支付宝公钥未配置，无法验签，直接拒绝");
            return ParseResult.reject("alipay_not_configured");
        }

        String sign = params.get("sign");
        if (sign == null || sign.isBlank()) {
            return ParseResult.reject("missing_sign");
        }
        try {
            // ⚠️ 必须传副本：AlipaySignature.getSignCheckContentV1 会**就地** remove("sign")
            // 和 remove("sign_type")（已反编译核实）。若直接把 params 递进去，
            // 验签成功后留档的原文里就没有 sign 了 —— 偏偏排障时最需要的就是当时的签名。
            boolean verified = AlipaySignature.rsaCheckV1(new LinkedHashMap<>(params),
                    cfg.getAlipayPublicKey(), cfg.getCharset(), cfg.getSignType());
            if (!verified) {
                log.warn("支付宝回调验签失败：outTradeNo={}, tradeStatus={}",
                        params.get("out_trade_no"), params.get("trade_status"));
                return ParseResult.reject("sign_verify_failed");
            }
        } catch (AlipayApiException e) {
            log.warn("支付宝回调验签异常：err={}", e.getMessage());
            return ParseResult.reject("sign_verify_error:" + e.getMessage());
        }

        String appId = params.get("app_id");
        if (!cfg.getAppId().equals(appId)) {
            // 验签通过但 app_id 不是本系统的：说明用的是别人的公钥/参数，必须拒绝
            log.warn("支付宝回调 app_id 不匹配：expected={}, actual={}", cfg.getAppId(), appId);
            return ParseResult.reject("app_id_mismatch");
        }

        String outTradeNo = params.get("out_trade_no");
        if (outTradeNo == null || outTradeNo.isBlank()) {
            return ParseResult.reject("missing_out_trade_no");
        }
        String tradeStatus = params.get("trade_status");
        if (tradeStatus == null || tradeStatus.isBlank()) {
            return ParseResult.reject("missing_trade_status");
        }
        Long amountFen = AlipayAmount.yuanToFen(params.get("total_amount"));
        if (amountFen == null) {
            return ParseResult.reject("unparsable_total_amount:" + params.get("total_amount"));
        }

        PayNotifyCommand command = new PayNotifyCommand()
                .setProvider(PayProvider.ALIPAY)
                .setOutTradeNo(outTradeNo)
                .setTradeNo(params.get("trade_no"))
                .setAmount(amountFen)
                .setSuccess(AlipayTradeStatus.isPaid(tradeStatus))
                .setRawStatus(tradeStatus)
                .setPayload(toPayload(params))
                .setNotifyTime(LocalDateTime.now());
        return new ParseResult(true, null, command);
    }

    /** 把表单参数还原成一段可读原文用于留档。{@code sign} 与 {@code sign_type} 照留 —— 排障时最需要的恰恰是
     * 「当时的签名是什么」；用有序 Map 保证同一份回调每次落库文本一致，便于对账比对。 */
    static String toPayload(Map<String, String> params) {
        Map<String, String> sorted = new LinkedHashMap<>(params);
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> sb.append(k).append('=').append(v).append('&'));
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }
}
