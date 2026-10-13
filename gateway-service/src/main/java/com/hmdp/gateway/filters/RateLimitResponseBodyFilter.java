package com.hmdp.gateway.filters;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 给限流的 429 响应补 JSON body，与 401 / 403 的错误格式统一。限流器拒绝时走 {@code setComplete()}（非 {@code writeWith}），body 为空，前端按 {@code {code,message}} 解析会拿到 undefined，故必须用响应装饰器覆写 {@code setComplete()}（覆写 {@code writeWith} 拦不到）。只在 429 且未提交时改写，对正常请求零影响。
 */
@Component
public class RateLimitResponseBodyFilter implements GlobalFilter, Ordered {

    private static final int TOO_MANY_REQUESTS = HttpStatus.TOO_MANY_REQUESTS.value();

    private static final String BODY = "{\"code\":429,\"message\":\"请求过于频繁，请稍后重试\"}";

    /** 固定 1 秒：让客户端退避即可，精确值反而诱导它卡点重试。 */
    private static final String RETRY_AFTER_SECONDS = "1";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        return chain.filter(exchange.mutate().response(new JsonRateLimitResponse(response)).build());
    }

    /** order = -1：必须早于路由级 RequestRateLimiter 装配装饰器，装饰器被动无副作用。 */
    @Override
    public int getOrder() {
        return -1;
    }

    private static final class JsonRateLimitResponse extends ServerHttpResponseDecorator {

        JsonRateLimitResponse(ServerHttpResponse delegate) {
            super(delegate);
        }

        @Override
        public Mono<Void> setComplete() {
            if (!shouldRewrite()) {
                return super.setComplete();
            }
            getDelegate().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            getDelegate().getHeaders().set("Retry-After", RETRY_AFTER_SECONDS);
            byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = getDelegate().bufferFactory().wrap(bytes);
            return getDelegate().writeWith(Mono.just(buffer));
        }

        /** 只认 429 且未提交（已提交说明下游真写了东西，别覆盖）。 */
        private boolean shouldRewrite() {
            HttpStatus status = getStatusCode();
            return status != null && status.value() == TOO_MANY_REQUESTS && !getDelegate().isCommitted();
        }
    }
}