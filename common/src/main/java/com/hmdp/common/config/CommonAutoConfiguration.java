package com.hmdp.common.config;

import com.hmdp.common.cache.CacheClient;
import com.hmdp.common.cache.SingleFlight;
import com.hmdp.common.utils.RedisIdWorker;
import com.hmdp.common.count.ViewCounter;
import com.hmdp.common.outbox.OutboxCanalHandler;
import com.hmdp.common.outbox.OutboxConsumeService;
import com.hmdp.common.outbox.OutboxReconcileTask;
import com.hmdp.common.outbox.OutboxWriter;
import com.hmdp.common.config.SwaggerProperties;
import com.hmdp.common.config.OpenApiConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 自动配置类，将缓存客户端、ID生成器、Redisson配置、Web异常处理、消息队列消费错误自动配置、MVC配置、消息队列配置、JSON配置和MyBatis配置导入为Spring容器的Bean
 */
@EnableScheduling
@Configuration
@Import({
        CacheClient.class,
        SingleFlight.class,
        RedisIdWorker.class,
        ViewCounter.class,
        RedissonConfig.class,
        OutboxWriter.class,
        OutboxConsumeService.class,
        OutboxReconcileTask.class,
        // OutboxCanalHandler.class,
        WebExceptionAdvice.class,
        SwaggerProperties.class,
        OpenApiConfig.class,
        MqConsumeErrorAutoConfiguration.class,
        MvcConfig.class,
        MqConfig.class,
        JsonConfig.class,
        MybatisConfig.class
})
public class CommonAutoConfiguration {
}
