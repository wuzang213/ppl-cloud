package com.hmdp.gateway.filters;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.gateway.config.AuthProperties;
import com.hmdp.gateway.config.JwtUtils;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 全局过滤器：RS256 JWT 签名校验 + TokenVersion 版本校验。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String TOKEN_VERSION_KEY = "login:token-version:";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final AuthProperties authProperties;
    private final JwtUtils jwtUtils;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    // Caffeine TTL 从 60 秒降至 10 秒，降低 kickAllDevices 后旧 token 可用窗口
    private final Cache<Long, Integer> tokenVersionCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(10, TimeUnit.SECONDS)
            .build();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (isExclude(request.getPath().toString())) {
            return chain.filter(exchange);
        }

        String authHeader = request.getHeaders().getFirst("Authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }
        if (token == null || token.isBlank()) {
            return unauthorized(exchange);
        }

        try {
            Claims claims = jwtUtils.parse(token);
            Object userIdValue = claims.get("userId");
            Object versionValue = claims.get("tokenVersion");
            if (!(userIdValue instanceof Number) || !(versionValue instanceof Number)) {
                return unauthorized(exchange);
            }
            Long userId = ((Number) userIdValue).longValue();
            Integer jwtVersion = ((Number) versionValue).intValue();

            Integer cached = tokenVersionCache.getIfPresent(userId);
            Mono<Integer> currentVersion;
            if (cached != null) {
                currentVersion = Mono.just(cached);
            } else {
                currentVersion = redisTemplate.opsForValue()
                        .get(TOKEN_VERSION_KEY + userId)
                        .map(Integer::valueOf)
                        .defaultIfEmpty(-1)
                        // 不缓存 -1（Redis 不可用时返回 -1 拒绝，但不缓存，Redis 恢复后立即可用）
                        .doOnNext(version -> {
                            if (version != -1) {
                                tokenVersionCache.put(userId, version);
                            }
                        })
                        .onErrorResume(e -> {
                            log.warn("获取 tokenVersion 失败，按鉴权失败处理，userId={}", userId, e);
                            return Mono.just(-1);
                        });
            }

            return currentVersion.flatMap(version -> {
                if (!Objects.equals(version, jwtVersion)) {
                    return unauthorized(exchange);
                }
                ServerHttpRequest.Builder builder = request.mutate();
                // 先清洗客户端可能伪造的敏感 header，防止下游拿到伪造的 user-info
                builder.headers(h -> {
                    h.remove("user-info");
                    h.remove("user-role");
                    h.remove("user-nickname");
                    h.remove("user-icon");
                });
                builder.header("user-info", userId.toString());
                if (claims.get("role", String.class) != null) {
                    builder.header("user-role", claims.get("role", String.class));
                }
                // JWT 不再携带 nickName/icon，网关不再透传这两个可变 header
                return chain.filter(exchange.mutate().request(builder.build()).build());
            });
        } catch (Exception e) {
            log.warn("JWT 校验失败，path={}", request.getPath(), e);
            return unauthorized(exchange);
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":401,\"message\":\"未登录或登录已失效\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private boolean isExclude(String path) {
        for (String pattern : authProperties.getExcludePaths()) {
            if (antPathMatcher.match(pattern, path)) {
                log.debug("放行白名单路径：{}", pattern);
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}