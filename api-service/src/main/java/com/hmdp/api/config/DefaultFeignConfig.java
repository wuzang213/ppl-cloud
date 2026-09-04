package com.hmdp.api.config;

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
}