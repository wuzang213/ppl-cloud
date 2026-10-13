package com.hmdp.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 免登录流量的限流 key：按客户端 IP。必须自定义：自带的 {@code PrincipalNameKeyResolver} 只能从已认证 exchange 取 principal，免登录的 {@code /search/**} 会返回空 key → 触发 {@code denyEmptyKey=true} → 全部 403；本 Bean 带 {@code @ConditionalOnMissingBean(KeyResolver.class)} 会自动顶替，无需在路由写 {@code key-resolver} SpEL。⚠️ 部署顺序：先发带本类的网关包，再改 Nacos 路由，反过来会让搜索全 403。
 */
@Slf4j
@Configuration
public class RateLimitConfig {

    /** 拿不到客户端地址时的兜底 key（这些请求共用一个令牌桶）。 */
    private static final String UNKNOWN_IP = "unknown";

    /** 是否信任 X-Forwarded-For。默认 false —— 该头客户端可伪造，盲目信任等于不限流。 */
    @Value("${hmdp.gateway.rate-limit.trust-x-forwarded-for:false}")
    private boolean trustXForwardedFor;

    @Bean
    public KeyResolver clientIpKeyResolver() {
        return exchange -> Mono.just(resolveClientIp(exchange));
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        if (trustXForwardedFor) {
            String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                // XFF 是逗号分隔链，第一段才是原始客户端
                int comma = forwardedFor.indexOf(',');
                String first = (comma > 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            log.debug("无法解析客户端地址，限流 key 退化为 {}", UNKNOWN_IP);
            return UNKNOWN_IP;
        }
        return remoteAddress.getAddress().getHostAddress();
    }
}