package com.hmdp.voucher.payment.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/** 预下单结果 —— 渠道返回给前端的「怎么付」信息。{@code kind} 决定前端如何渲染 {@code payload}：{@code url}
 * （直接跳转，支付宝电脑网站支付）、{@code code_url}（二维码字符串，微信 Native 扫码）、{@code h5_url}（手机浏览器内
 * 跳转，微信 H5）。不可枚举成固定字段：硬做成 {@code payUrl} 会让微信两条渠道没法表达。 */
@Getter
@ToString
@AllArgsConstructor
public class PrepayResult {

    /** 渠道 code，回显给前端便于排查（与 tb_payment.channel 一致） */
    private final String channel;

    /** 负载类型：url / code_url / h5_url */
    private final String kind;

    /** 负载本体，落库到 tb_payment.prepay_payload */
    private final String payload;

    public static PrepayResult url(String channel, String payUrl) {
        return new PrepayResult(channel, "url", payUrl);
    }

    public static PrepayResult codeUrl(String channel, String codeUrl) {
        return new PrepayResult(channel, "code_url", codeUrl);
    }

    public static PrepayResult h5Url(String channel, String h5Url) {
        return new PrepayResult(channel, "h5_url", h5Url);
    }

    /** 按渠道 code 还原 {@code kind}。落库只存了 {@code payload} 字符串，复用时必须能还原出「这是什么形态」，
     * 否则复用路径会把二维码串当成跳转 URL 返回给前端。
     * @throws IllegalArgumentException 未登记渠道（调用方应已由注册表拦下，这里是最后一道防线） */
    public static PrepayResult forChannel(String channelCode, String payload) {
        PayChannelType type = PayChannelType.fromCode(channelCode);
        if (type == null) {
            throw new IllegalArgumentException("未知支付渠道，无法还原支付串形态: " + channelCode);
        }
        switch (type) {
            case WECHAT_NATIVE:
                return codeUrl(channelCode, payload);
            case WECHAT_H5:
                return h5Url(channelCode, payload);
            case ALIPAY_PAGE:
            case MOCK:
            default:
                return url(channelCode, payload);
        }
    }

    public boolean isEmpty() {
        return payload == null || payload.isBlank();
    }
}
