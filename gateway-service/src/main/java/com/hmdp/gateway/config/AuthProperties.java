package com.hmdp.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "hmdp.auth")
public class AuthProperties {
    private List<String> includePaths;
    private List<String> excludePaths;

    private String keyStorePath = "jwt/public.jks";

    private String keyStorePassword = "你的密钥库密码";

    private String keyStoreAlias = "你的密钥库别名";
}
