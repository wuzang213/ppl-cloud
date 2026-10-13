package com.hmdp.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "hmdp.auth")
public class AuthProperties {

    /**
     * ⚠️ 死字段：全仓无代码读取（grep getIncludePaths = 0 命中），yaml 也未配置；当前实现是「除白名单外一律要求登录」，它没有存在必要，保留仅为兼容旧配置，不要指望改它能生效。
     */
    private List<String> includePaths;

    private List<String> excludePaths;

    /**
     * 内部接口黑名单：优先级高于白名单，命中即 403。{@code excludePaths} 里的 {@code /search/**} 在 Ant 匹配下会同时覆盖 {@code /search/admin/**}（全量重建、一致性修复），只靠"不在白名单"挡不住内部口子。⚠️ 初始化为空集合（而非 null）：yaml 未配置时遍历不能 NPE。
     */
    private List<String> internalPaths = new ArrayList<>();

    private String keyStorePath = "jwt/public.jks";

    private String keyStorePassword = "你的密钥库密码";

    private String keyStoreAlias = "你的密钥库别名";
}
