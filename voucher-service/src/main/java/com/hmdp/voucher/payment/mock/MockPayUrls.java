package com.hmdp.voucher.payment.mock;

import com.hmdp.voucher.payment.config.PayProperties;

/** mock 渠道用到的地址拼装（唯一一处，避免收银台 URL 与投递 URL 各写一份后漂移）。基址取值优先级：
 * {@code hmdp.pay.mock.base-url} → {@code http://127.0.0.1:{server.port}}；可配是为了验收环境不一定从本机访问。 */
public final class MockPayUrls {

    /** 收银台（只展示，无副作用） */
    public static final String CASHIER_PATH = "/voucher-order/pay/mock/cashier/";

    /** 触发一次回调投递（<b>有副作用</b>：会真的改变订单状态） */
    public static final String PAY_PATH = "/voucher-order/pay/mock/pay/";

    /** 只吐出签名合法的报文，不投递（供人工 curl 打真实端点，验证网关白名单/篡改场景） */
    public static final String PAYLOAD_PATH = "/voucher-order/pay/mock/payload/";

    /** 支付宝真实回调端点 —— mock 投递的目标就是它，不是另开一条旁路 */
    public static final String NOTIFY_ALIPAY_PATH = "/voucher-order/pay/notify/alipay";

    /** 微信真实回调端点 */
    public static final String NOTIFY_WECHAT_PATH = "/voucher-order/pay/notify/wechat";

    private MockPayUrls() {
    }

    /** 解析基址。
     * @param configured {@code PayProperties.Mock#getBaseUrl()}，可空 @param serverPort 本服务端口（来自 {@code server.port}） */
    public static String baseUrl(String configured, int serverPort) {
        if (configured != null && !configured.isBlank()) {
            String trimmed = configured.trim();
            // 去掉尾部斜杠，否则拼出来是 //voucher-order/...（部分容器会 404）
            return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        }
        return "http://127.0.0.1:" + serverPort;
    }

    public static String cashier(String baseUrl, String outTradeNo) {
        return baseUrl + CASHIER_PATH + outTradeNo;
    }

    public static String pay(String baseUrl, String outTradeNo, String provider, boolean success) {
        return baseUrl + PAY_PATH + outTradeNo + "?provider=" + provider + "&success=" + success;
    }

    public static String payload(String baseUrl, String outTradeNo, String provider) {
        return baseUrl + PAYLOAD_PATH + outTradeNo + "?provider=" + provider;
    }

    public static String notify(String baseUrl, String provider) {
        return baseUrl + ("wechat".equalsIgnoreCase(provider) ? NOTIFY_WECHAT_PATH : NOTIFY_ALIPAY_PATH);
    }

    /** 便捷方法，供只拿到 {@link PayProperties} 的调用方使用。 */
    public static String baseUrl(PayProperties properties, int serverPort) {
        return baseUrl(properties.getMock().getBaseUrl(), serverPort);
    }
}
