package com.hmdp.voucher.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hmdp.voucher.payment.dto.PaymentVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单详情接口的<b>JSON 契约</b>单测。
 *
 * <p>需求是「{@code GET /voucher-order/{id}} 返回值<b>只增</b>一个 payment 字段」。
 * 「只增」这种要求最容易在重构中悄悄破掉（字段少返回一个，前端某处显示空白，没人发现），
 * 所以这里不看源码、直接<b>比对两个对象序列化出来的 JSON</b>：
 * 键集合必须恰好等于「实体的键 + payment」，且每个键的值逐一相等。
 *
 * <p>它还顺带钉死了一个真实的坑：{@link VoucherOrder} 带 Lombok {@code @Accessors(chain = true)}，
 * setter 返回 {@code this} 而非 {@code void}，而 JDK 的 {@code Introspector} 只认返回 void 的
 * setXxx 为写方法 —— 于是 {@code BeanUtils.copyProperties(order, vo)} 会<b>静默拷不到任何字段</b>，
 * 接口返回一堆 null 却不报错。谁要把它改回反射拷贝，这个测试立刻红。
 */
class VoucherOrderDetailVOTest {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static VoucherOrder fullOrder() {
        VoucherOrder order = new VoucherOrder();
        order.setId(20002L);
        order.setUserId(7L);
        order.setVoucherId(9001L);
        order.setPayType(2);
        order.setStatus(OrderStatus.PAID.getValue());
        order.setCreateTime(LocalDateTime.of(2026, 9, 26, 10, 0));
        order.setPayTime(LocalDateTime.of(2026, 9, 26, 10, 5));
        order.setUseTime(LocalDateTime.of(2026, 9, 26, 11, 0));
        order.setRefundTime(LocalDateTime.of(2026, 9, 26, 12, 0));
        order.setUpdateTime(LocalDateTime.of(2026, 9, 26, 12, 0));
        return order;
    }

    private static PaymentVO paidPaymentVO() {
        Payment payment = new Payment()
                .setId(1000L).setOrderId(20002L).setUserId(7L)
                .setChannel("alipay_page").setAmount(1000L).setFaceValue(10000L)
                .setStatus(1).setOutTradeNo("PAY1001").setTradeNo("TRADE-1")
                .setPayTime(LocalDateTime.of(2026, 9, 26, 10, 5))
                .setPrepayPayload("https://openapi.alipay.com/gateway.do?...")
                .setNotifyPayload("app_id=x&sign=SECRET")
                .setNotifyTime(LocalDateTime.of(2026, 9, 26, 10, 5));
        return PaymentVO.of(payment);
    }

    @Test
    @DisplayName("详情 JSON：字段集合恰好等于「订单字段 + payment」，值逐字段不变")
    void detailJsonIsOrderPlusPaymentOnly() {
        VoucherOrder order = fullOrder();

        Map<String, Object> orderJson = MAPPER.convertValue(order, MAP_TYPE);
        Map<String, Object> detailJson = MAPPER.convertValue(
                VoucherOrderDetailVO.of(order, paidPaymentVO()), MAP_TYPE);

        Set<String> keysWithoutPayment = new LinkedHashSet<>(detailJson.keySet());
        assertTrue(keysWithoutPayment.remove("payment"), "必须新增 payment 字段");
        assertEquals(orderJson.keySet(), keysWithoutPayment,
                "除 payment 外的字段集合必须与原实体完全一致（少一个即破契约）");

        for (String key : orderJson.keySet()) {
            assertEquals(orderJson.get(key), detailJson.get(key), "字段 " + key + " 的值被改动了");
        }
        assertNotNull(detailJson.get("payment"));
    }

    @Test
    @DisplayName("订单为空时不应炸（字段留空、payment 照常填）")
    void nullOrderDoesNotExplode() {
        Map<String, Object> json = MAPPER.convertValue(
                VoucherOrderDetailVO.of(null, paidPaymentVO()), MAP_TYPE);

        assertNull(json.get("id"));
        assertNotNull(json.get("payment"));
    }

    @Test
    @DisplayName("支付单视图只暴露可对外字段：回调原文/回调时间绝不外泄")
    void paymentViewHidesInternalNotifyData() {
        Map<String, Object> json = MAPPER.convertValue(paidPaymentVO(), MAP_TYPE);

        assertFalse(json.containsKey("notifyPayload"), "回调原文含渠道签名，属内部对账资料");
        assertFalse(json.containsKey("notifyTime"));
        assertEquals(1000L, json.get("amount"), "金额单位是分，不做换算");
        assertEquals(10000L, json.get("faceValue"));
        assertEquals("PAY1001", json.get("outTradeNo"));
        assertEquals("alipay_page", json.get("channel"));
        assertNotNull(json.get("prepayPayload"), "支付串要给前端，用于重绘支付入口");
    }

    @Test
    @DisplayName("没有支付单时视图为 null（订单从未发起支付是正常情况）")
    void nullPaymentMapsToNull() {
        assertNull(PaymentVO.of(null));

        Map<String, Object> json = MAPPER.convertValue(
                VoucherOrderDetailVO.of(fullOrder(), null), MAP_TYPE);
        assertTrue(json.containsKey("payment"), "字段要在，只是值为 null");
        assertNull(json.get("payment"));
    }
}
