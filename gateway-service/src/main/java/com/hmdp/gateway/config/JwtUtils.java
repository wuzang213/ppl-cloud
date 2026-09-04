package com.hmdp.gateway.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.cert.Certificate;

/**
 * 网关 JWT 公钥校验，从 classpath 下的 JKS 加载公钥证书。
 */
@Component
public class JwtUtils {

    private final PublicKey publicKey;

    public JwtUtils(AuthProperties properties) throws Exception {
        try (InputStream in = new ClassPathResource(properties.getKeyStorePath()).getInputStream()) {
            char[] password = properties.getKeyStorePassword().toCharArray();
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(in, password);
            Certificate certificate = keyStore.getCertificate(properties.getKeyStoreAlias());
            if (certificate == null) {
                throw new IllegalArgumentException("JKS 中未找到别名 " + properties.getKeyStoreAlias());
            }
            this.publicKey = certificate.getPublicKey();
        }
    }

    public Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(publicKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}