package com.hmdp.api.config;

import com.hmdp.api.client.fallback.BlogClientFallback;
import com.hmdp.api.client.fallback.ShopClientFallback;
import com.hmdp.api.client.fallback.UserClientFallback;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.utils.UserHolder;
import feign.Logger;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.context.annotation.Bean;

public class DefaultFeignConfig {

    @Bean
    public Logger.Level feignLogLevel() {
        return Logger.Level.FULL;
    }

    @Bean
    public RequestInterceptor userInfoRequestInterceptor() {
        return template -> {
            UserDTO user = UserHolder.getUser();
            if (user == null || user.getId() == null) {
                return;
            }
            template.header("user-info", user.getId().toString());
            if (user.getNickName() != null) {
                template.header("user-nickname", user.getNickName());
            }
            if (user.getIcon() != null) {
                template.header("user-icon", user.getIcon());
            }
        };
    }

    @Bean
    public UserClientFallback userClientFallback() {
        return new UserClientFallback();
    }

    /**
     * 搜索文档反查（search-service 用）的降级工厂。返回 {@code Result.fail(...)} 而非 {@code Result.ok(null)}，search-service 才能区分「调用失败（要重试）」与「文档已删除（要删索引）」。
     */
    @Bean
    public BlogClientFallback blogClientFallback() {
        return new BlogClientFallback();
    }

    @Bean
    public ShopClientFallback shopClientFallback() {
        return new ShopClientFallback();
    }
}