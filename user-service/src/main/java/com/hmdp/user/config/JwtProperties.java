package com.hmdp.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.jwt")
public class JwtProperties {

    private String keyStorePath = "jwt/private.jks";

    private String keyStorePassword = "你的密钥密码";

    private String keyStoreAlias = "你的密钥别名";

    private long accessTtlMinutes = 60L;

    private long refreshTtlDays = 7L;
}
