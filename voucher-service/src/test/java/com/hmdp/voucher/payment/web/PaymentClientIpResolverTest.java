package com.hmdp.voucher.payment.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link PaymentClientIpResolver} 的纯逻辑单测（零依赖，不伪造 {@code HttpServletRequest}）。
 *
 * <p>为什么值得单测：这个 IP 要填进微信 H5 的 {@code scene_info.payer_client_ip}，
 * 而<b>填错不会报错</b>（微信只拿它做风控定位）——
 * 典型故障是「网关在前面，取到的是网关 IP」，或者「IPv6 被按第一个冒号切成了 {@code 2001}」。
 * 这类静默错误只能靠测试钉住。
 */
class PaymentClientIpResolverTest {

    @Test
    @DisplayName("信任 XFF 时取「最后一段」——网关是追加语义，最后一段才是它照实填的")
    void takesLastForwardedHop() {
        assertEquals("203.0.113.7",
                PaymentClientIpResolver.pick("203.0.113.7", null, "10.0.0.1", true));
        // 客户端伪造的第一段必须被忽略
        assertEquals("203.0.113.7",
                PaymentClientIpResolver.pick("1.2.3.4, 203.0.113.7", null, "10.0.0.1", true));
        assertEquals("203.0.113.7",
                PaymentClientIpResolver.pick("1.2.3.4,203.0.113.7 ", null, "10.0.0.1", true));
    }

    @Test
    @DisplayName("XFF 里的 unknown / 空段要跳过，不能把 'unknown' 当 IP 用")
    void skipsUnknownHops() {
        assertEquals("1.2.3.4",
                PaymentClientIpResolver.pick("1.2.3.4, unknown", null, "10.0.0.1", true));
        assertEquals("1.2.3.4",
                PaymentClientIpResolver.pick("1.2.3.4, ", null, "10.0.0.1", true));
        // 全是无效值 → 退回 remoteAddr
        assertEquals("10.0.0.1",
                PaymentClientIpResolver.pick("unknown, ", null, "10.0.0.1", true));
    }

    @Test
    @DisplayName("XFF 缺失时退回 X-Real-IP，再退回 remoteAddr")
    void fallsBackInOrder() {
        assertEquals("198.51.100.9",
                PaymentClientIpResolver.pick(null, "198.51.100.9", "10.0.0.1", true));
        assertEquals("198.51.100.9",
                PaymentClientIpResolver.pick("  ", "198.51.100.9", "10.0.0.1", true));
        assertEquals("10.0.0.1",
                PaymentClientIpResolver.pick(null, null, "10.0.0.1", true));
    }

    @Test
    @DisplayName("不信任转发头（默认开关关掉时）→ 一律用 TCP 源地址，伪造头无效")
    void ignoresForwardedHeadersWhenNotTrusted() {
        assertEquals("10.0.0.1",
                PaymentClientIpResolver.pick("1.2.3.4, 203.0.113.7", "198.51.100.9", "10.0.0.1", false));
        assertNull(PaymentClientIpResolver.pick("1.2.3.4", "198.51.100.9", null, false));
    }

    @Test
    @DisplayName("必须去掉端口：微信只接受纯 IP")
    void stripsPort() {
        assertEquals("10.0.0.1",
                PaymentClientIpResolver.pick(null, null, "10.0.0.1:54321", true));
        assertEquals("203.0.113.7",
                PaymentClientIpResolver.pick("203.0.113.7:5000", null, "10.0.0.1", true));
    }

    @Test
    @DisplayName("IPv6：带方括号的去掉端口，裸地址原样保留（绝不能按第一个冒号切）")
    void handlesIpv6() {
        assertEquals("[2001:db8::1]",
                PaymentClientIpResolver.pick(null, null, "[2001:db8::1]:8080", true));
        assertEquals("[2001:db8::1]",
                PaymentClientIpResolver.pick("1.2.3.4, [2001:db8::1]", null, "10.0.0.1", true));
        assertEquals("2001:db8::1",
                PaymentClientIpResolver.pick(null, null, "2001:db8::1", true),
                "裸 IPv6 若按第一个冒号切会变成 '2001' —— 语法合法、语义全错");
    }

    @Test
    @DisplayName("什么都没有时返回 null（由调用方按「缺 IP」处理，而不是编一个）")
    void returnsNullWhenNothingAvailable() {
        assertNull(PaymentClientIpResolver.pick(null, null, null, true));
        assertNull(PaymentClientIpResolver.pick(" ", " ", "  ", true));
    }
}
