package com.hmdp.gateway.filters;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关审计：记录操作 IP、方法、路径、状态码和调用耗时。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        ServerHttpRequest request = exchange.getRequest();
        String ip = request.getRemoteAddress() == null
                ? "unknown" : request.getRemoteAddress().getAddress().getHostAddress();
        return chain.filter(exchange).doFinally(signal -> {
            long cost = System.currentTimeMillis() - start;
            log.info("audit ip={} method={} path={} status={} cost={}ms",
                    ip, request.getMethod(), request.getPath(),
                    exchange.getResponse().getStatusCode(), cost);
        });
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
