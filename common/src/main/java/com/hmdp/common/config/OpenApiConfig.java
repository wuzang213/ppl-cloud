package com.hmdp.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class OpenApiConfig {

    private final SwaggerProperties swaggerProperties;

    @Bean
    public OpenAPI hmdpOpenAPI() {
        return new OpenAPI().info(new Info()
                .title(swaggerProperties.getTitle())
                .version("1.0.0"));
    }

    @Bean
    public GroupedOpenApi hmdpGroup() {
        return GroupedOpenApi.builder()
                .group(swaggerProperties.getTitle())
                .packagesToScan(swaggerProperties.getPackageName())
                .pathsToMatch("/**")
                .build();
    }
}
