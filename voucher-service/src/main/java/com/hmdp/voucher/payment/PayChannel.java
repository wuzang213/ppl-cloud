package com.hmdp.voucher.payment;

import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.domain.PayChannelType;
import com.hmdp.voucher.payment.domain.PayQueryResult;
import com.hmdp.voucher.payment.domain.PrepayResult;

/** 支付渠道策略接口。一个实现对应一个 {@link PayChannelType}，实现类都是 Spring Bean，由 {@link PayChannelRegistry}
 * 按 {@link #code()} 装配。两渠道形态不同：支付宝 {@code pageExecute} 纯本地拼参数 + 签名、不发网络请求（可零网络单测）；
 * 微信 {@code prepay} 会真发一次 HTTP 换 {@code code_url}/{@code h5_url}（渠道协议要求），故只能离线验「通知解析 + 验签装配」。 */
public interface PayChannel {

    /** 渠道 code。必须与 {@link PayChannelType#getCode()} 完全一致且全渠道唯一（重复会在 {@link PayChannelRegistry} 构造时 fail fast）。 */
    String code();

    /** 该渠道对应的类型枚举。
     * @throws IllegalStateException code 没登记到 {@link PayChannelType} */
    default PayChannelType type() {
        PayChannelType t = PayChannelType.fromCode(code());
        if (t == null) {
            throw new IllegalStateException("渠道 code 未登记到 PayChannelType: " + code());
        }
        return t;
    }

    /** 凭据是否齐备。用于区分两种失败：请求不存在的渠道 → 400（用户的错）；存在但没配凭据 → 「渠道未配置」
     * （运维的错）。混成一个错误会让「Nacos 忘了填凭据」表现成「渠道不支持」，很难排查。 */
    default boolean isReady() {
        return true;
    }

    /** 预下单：返回前端如何付款。{@code payment} 已带齐 {@code out_trade_no}、{@code amount}（分）与渠道，
     * <b>实现不得自行计算或改写金额</b>（金额只能来自支付单，且只由服务端计价产生）。
     * @param payment 已落库支付单（含 outTradeNo / amount，单位分）@param clientIp 用户端 IP，可空；微信 H5 必填（{@code scene_info.payer_client_ip}），支付宝电脑网站支付忽略 */
    PrepayResult prepay(Payment payment, String clientIp);

    /** 不带客户端 IP 的便捷重载。只有确实不需要 IP 的渠道/调用点才该用它 ——
     * 微信 H5 走这条路会因缺 {@code payer_client_ip} 被渠道拒绝。 */
    default PrepayResult prepay(Payment payment) {
        return prepay(payment, null);
    }

    /**
     * 主动查单：回调丢失时的兜底，按渠道侧真实结果决定推进到哪个终态。
     */
    PayQueryResult query(Payment payment);
}
