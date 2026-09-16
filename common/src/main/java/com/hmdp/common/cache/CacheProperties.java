package com.hmdp.common.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缓存开关配置。
 *
 */
@Data
@ConfigurationProperties(prefix = "hmdp.cache")
public class CacheProperties {

    /**
     * 是否启用多级缓存（Caffeine + Redis + 布隆过滤器 + SingleFlight）。
     *
     */
    private boolean enabled = true;
}
