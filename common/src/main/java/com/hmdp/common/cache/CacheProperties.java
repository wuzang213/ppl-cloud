package com.hmdp.common.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 缓存开关配置（hmdp.cache）：同一份代码经 hmdp.cache.enabled 在「多级缓存」与「直连数据库」间切换，
 * 便于压测对照；只控制店铺/博客详情缓存路径，ViewCounter.asyncIncr 不受本开关控制。 */
@Data
@ConfigurationProperties(prefix = "hmdp.cache")
public class CacheProperties {

    /** 是否启用多级缓存（Caffeine + Redis + 布隆过滤器 + SingleFlight）：
     * true（默认）走完整链路，false 跳过全部缓存直查数据库。 */
    private boolean enabled = true;
}
