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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 全局过滤器：内部口子黑名单（403） + RS256 JWT 签名校验 + TokenVersion 版本校验。判定顺序固定不可调换：{@code internalPaths} 黑名单（命中即 403，优先级最高）→ {@code excludePaths} 白名单（命中放行）→ 其余要求 {@code Authorization: Bearer <RS256 JWT>}，失败 401。黑名单必须排在白名单之前：白名单的 {@code /search/**} 在 Ant 匹配下会连带覆盖 {@code /search/admin/**}（全量重建/一致性修复），先判白名单就等于把运维口子对公网敞开。
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
        String path = request.getPath().toString();
        // ① 内部口子黑名单必须在最前：白名单里的 /search/** 会连带覆盖 /search/admin/**
        if (isInternal(path)) {
            return forbidden(exchange);
        }
        // ② 免登录白名单
        if (isExclude(path)) {
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

    /**
     * 内部口子命中：403。401 是"你没证明你是谁"，403 是"证明了也不许访问"；内部口子不应靠 401 兜底，因为 401 只是"不在白名单"的副产品，白名单一旦配宽内部口子就会一起放开。
     */
    private Mono<Void> forbidden(ServerWebExchange exchange) {
        log.warn("拦截内部接口访问：path={}, remoteAddr={}",
                exchange.getRequest().getPath(),
                exchange.getRequest().getRemoteAddress());
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":403,\"message\":\"无权访问该接口\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /** 是否命中内部接口黑名单。null 安全：yaml 未配置时按"无黑名单"处理。 */
    private boolean isInternal(String path) {
        List<String> internalPaths = authProperties.getInternalPaths();
        if (internalPaths == null) {
            return false;
        }
        for (String pattern : internalPaths) {
            if (pattern != null && antPathMatcher.match(pattern, path)) {
                log.debug("拦截内部接口路径：{}", pattern);
                return true;
            }
        }
        return false;
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