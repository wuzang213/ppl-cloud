package com.hmdp.common.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * common 模块的自动配置入口（Starter 机制）。
 */
@Configuration
@EnableScheduling
@ComponentScan(basePackages = "com.hmdp.common",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = CommonAutoConfiguration.class))
public class CommonAutoConfiguration {
}
