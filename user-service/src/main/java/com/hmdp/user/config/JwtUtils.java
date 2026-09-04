package com.hmdp.user.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.Date;

/**
 * RS256 JWT 签发工具，从 classpath 下的 JKS 加载私钥。
 */
@Component
public class JwtUtils {

    private final PrivateKey privateKey;

    private final JwtProperties properties;

    public JwtUtils(JwtProperties properties) throws Exception {
        this.properties = properties;
        try (InputStream in = new ClassPathResource(properties.getKeyStorePath()).getInputStream()) {
            char[] password = properties.getKeyStorePassword().toCharArray();
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(in, password);
            this.privateKey = (PrivateKey) keyStore.getKey(properties.getKeyStoreAlias(), password);
        }
        if (privateKey == null) {
            throw new IllegalArgumentException("JKS 中未找到别名 " + properties.getKeyStoreAlias());
        }
    }

    public String createAccessToken(Long userId, Integer tokenVersion, String deviceId, String role, String nickName, String icon) {
        Date now = new Date();
        long ttlMs = properties.getAccessTtlMinutes() * 60_000L;
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("tokenVersion", tokenVersion)
                .claim("deviceId", deviceId)
                .claim("role", role)
                .claim("nickName", nickName)
                .claim("icon", icon)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + ttlMs))
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }
}