package com.hmdp.voucher.payment;

import com.hmdp.common.exception.BadRequestException;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.domain.PayChannelType;
import com.hmdp.voucher.payment.domain.PayProvider;
import com.hmdp.voucher.payment.domain.PayQueryResult;
import com.hmdp.voucher.payment.domain.PrepayResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PayChannelRegistry} 的纯逻辑单测。零外部依赖（渠道实现是测试内的匿名类）。
 *
 * <p>重点是三类<b>装配期</b>错误必须 fail fast，而不是等到运行期才以奇怪的方式暴露：
 * code 为空、code 未登记到枚举、code 重复。
 */
class PayChannelRegistryTest {

    private static PayChannel channel(String code) {
        return new PayChannel() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public PrepayResult prepay(Payment payment, String clientIp) {
                return PrepayResult.url(code, "http://mock/" + payment.getOutTradeNo());
            }

            @Override
            public PayQueryResult query(Payment payment) {
                return PayQueryResult.pending("PENDING");
            }
        };
    }

    @Test
    @DisplayName("按 code 取到同一个实例；code 大小写与首尾空格不敏感")
    void get_isCaseAndTrimInsensitive() {
        PayChannel alipay = channel(PayChannelType.ALIPAY_PAGE.getCode());
        PayChannelRegistry registry = new PayChannelRegistry(Collections.singletonList(alipay));

        assertSame(alipay, registry.get("alipay_page"));
        assertSame(alipay, registry.get("ALIPAY_PAGE"));
        assertSame(alipay, registry.get(" alipay_page "));
        assertSame(alipay, registry.get(PayChannelType.ALIPAY_PAGE));
    }

    @Test
    @DisplayName("非法 channel → 400，且错误消息里回显可用渠道")
    void get_unknownCodeIsBadRequest() {
        PayChannelRegistry registry =
                new PayChannelRegistry(Collections.singletonList(channel(PayChannelType.MOCK.getCode())));

        BadRequestException e = assertThrows(BadRequestException.class, () -> registry.get("paypal"));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("mock"), "错误消息应回显可用渠道，便于前端自查: " + e.getMessage());
    }

    @Test
    @DisplayName("mock 未装配时请求 channel=mock → 400（枚举里有 MOCK 不等于它会放行）")
    void mockNotAssembledIsBadRequest() {
        PayChannelRegistry registry = new PayChannelRegistry(
                Collections.singletonList(channel(PayChannelType.ALIPAY_PAGE.getCode())));

        BadRequestException e = assertThrows(BadRequestException.class, () -> registry.get("mock"));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("alipay_page"), e.getMessage());
        assertFalse(e.getMessage().contains("mock"),
                "未装配的渠道不应出现在可用列表里，否则会误导调用方: " + e.getMessage());

        // find() 是给后台任务用的：拿到库里的脏值/历史值应得到 null，而不是一个面向用户的 400
        assertNull(registry.find("mock"));
    }

    @Test
    @DisplayName("codes() 字典序稳定（日志与断言可比）")
    void codes_sorted() {
        PayChannelRegistry registry = new PayChannelRegistry(Arrays.asList(
                channel("wechat_h5"), channel("alipay_page"), channel("wechat_native")));
        assertEquals(List.of("alipay_page", "wechat_h5", "wechat_native"),
                List.copyOf(registry.codes()));
    }

    @Test
    @DisplayName("code 重复 → 启动即失败（否则会静默覆盖）")
    void duplicateCodeFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PayChannelRegistry(Arrays.asList(channel("mock"), channel("MOCK"))));
        assertTrue(e.getMessage().contains("重复"), e.getMessage());
    }

    @Test
    @DisplayName("code 未登记到 PayChannelType → 启动即失败（防枚举与实现脱节）")
    void unregisteredCodeFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PayChannelRegistry(Collections.singletonList(channel("paypal"))));
        assertTrue(e.getMessage().contains("未登记"), e.getMessage());
    }

    @Test
    @DisplayName("code 为空 → 启动即失败")
    void blankCodeFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new PayChannelRegistry(Collections.singletonList(channel("  "))));
    }

    @Test
    @DisplayName("渠道 → 服务商映射：pay_type 由服务商决定，不是由渠道反推")
    void providerMapping() {
        assertEquals(PayProvider.ALIPAY, PayChannelType.ALIPAY_PAGE.getProvider());
        assertEquals(PayProvider.WECHAT, PayChannelType.WECHAT_NATIVE.getProvider());
        assertEquals(PayProvider.WECHAT, PayChannelType.WECHAT_H5.getProvider());
        // tb_voucher_order.pay_type：2 支付宝 / 3 微信（1 是余额支付，未实现）
        assertEquals(2, PayProvider.ALIPAY.getOrderPayType());
        assertEquals(3, PayProvider.WECHAT.getOrderPayType());
    }
}
