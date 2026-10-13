package com.hmdp.voucher.payment.domain;

/** 支付渠道类型。{@link #getCode()} 的字符串就是落库到 {@code tb_payment.channel} 的值，
 * 也是请求体 {@code {"channel":"..."}} 的唯一合法取值集合。 */
public enum PayChannelType {

    ALIPAY_PAGE("alipay_page", PayProvider.ALIPAY, "支付宝电脑网站支付"),
    WECHAT_NATIVE("wechat_native", PayProvider.WECHAT, "微信 Native 扫码支付"),
    WECHAT_H5("wechat_h5", PayProvider.WECHAT, "微信 H5 支付"),
    /** 验收用替身渠道：默认关闭，prod profile 下配成开启会启动失败。它复用真实的回调 Controller / 验签逻辑 / 状态机，
     * 只替换「渠道那一跳」。 */
    MOCK("mock", PayProvider.ALIPAY, "验收用 mock 渠道（默认关闭）");

    private final String code;
    private final PayProvider provider;
    private final String desc;

    PayChannelType(String code, PayProvider provider, String desc) {
        this.code = code;
        this.provider = provider;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public PayProvider getProvider() {
        return provider;
    }

    public String getDesc() {
        return desc;
    }

    /** 按 code 反查；非法 code 返回 {@code null}，由调用方抛业务异常（→ 400）。 */
    public static PayChannelType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (PayChannelType t : values()) {
            if (t.code.equalsIgnoreCase(code.trim())) {
                return t;
            }
        }
        return null;
    }
}
