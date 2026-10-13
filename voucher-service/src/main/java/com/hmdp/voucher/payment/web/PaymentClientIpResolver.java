package com.hmdp.voucher.payment.web;

import com.hmdp.voucher.payment.config.PayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

/** 取「用户端 IP」—— 只服务于微信 H5 支付必填的 {@code scene_info.payer_client_ip}。不用 {@code getRemoteAddr()}：本项目
 * 网关在最前面，voucher-service 看到的 TCP 源地址永远是网关，用它等于把网关 IP 报给微信（微信不报错、只拿去做风控定位，
 * 属静默填错），故默认读 {@code X-Forwarded-For} 并受 {@link PayProperties#isTrustXForwardedFor()} 约束。取 XFF 最后一段而非第一段：Spring Cloud Gateway 的 {@code XForwardedHeadersFilter} 是追加语义（header 形态 {@code <客户端自称的>, <真实客户端IP>}），第一段可随意伪造、最后一段是网关照实填的（代价：网关后面还有一层追加型代理时最后一段会变成那层代理的地址）；本仓库链路只有网关一层。该 IP 只影响微信风控定位，不参与任何金额或归属判断。 */
@Component
@Slf4j
public class PaymentClientIpResolver {

    private final PayProperties payProperties;

    public PaymentClientIpResolver(PayProperties payProperties) {
        this.payProperties = payProperties;
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return pick(request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getRemoteAddr(),
                payProperties.isTrustXForwardedFor());
    }

    /** 纯逻辑版本（便于零依赖单测，不需要伪造 {@code HttpServletRequest}）。
     * @param forwardedFor {@code X-Forwarded-For} 原始值（可能是一串逗号分隔地址）@param realIp {@code X-Real-IP} 原始值
     * @param remoteAddr {@code HttpServletRequest#getRemoteAddr()} @param trust 是否信任转发头（false 时一律用 remoteAddr）@return 清洗后的 IP；无可用值时返回 {@code null}（由调用方按「缺 IP」处理） */
    static String pick(String forwardedFor, String realIp, String remoteAddr, boolean trust) {
        if (trust) {
            String last = lastForwardedHop(forwardedFor);
            if (last != null) {
                return last;
            }
            if (realIp != null && !realIp.isBlank()) {
                return stripPort(realIp.trim());
            }
        }
        return stripPort(remoteAddr);
    }

    /** XFF 是逗号分隔链，取最后一段（见类注释：网关是「追加」语义）。 */
    private static String lastForwardedHop(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return null;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !"unknown".equalsIgnoreCase(hop)) {
                return stripPort(hop);
            }
        }
        return null;
    }

    /** 去掉 {@code host:port} 里的端口。微信要的是纯 IP，带上 {@code :端口} 会被判成非法值。三种形态要分开处理，否则会把
     * IPv6 切坏：{@code 1.2.3.4:5678} → {@code 1.2.3.4}；{@code [2001:db8::1]:8080} → {@code [2001:db8::1]}（保留方括号）；
     * 裸 IPv6 {@code 2001:db8::1} 原样返回 —— 绝不能按第一个冒号切，那会把它切成 {@code 2001}（语法合法、语义完全错误的 IP）。 */
    private static String stripPort(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            return end > 0 ? v.substring(0, end + 1) : v;
        }
        int firstColon = v.indexOf(':');
        if (firstColon < 0) {
            return v;
        }
        // 冒号多于一个 → 裸 IPv6，没有端口可去
        if (v.indexOf(':', firstColon + 1) > 0) {
            return v;
        }
        return v.substring(0, firstColon);
    }
}
