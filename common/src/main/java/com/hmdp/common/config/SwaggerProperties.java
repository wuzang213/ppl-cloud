package com.hmdp.common.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Data
@Component
public class SwaggerProperties {

    @Value("${hmdp.swagger.title:}")
    private String title;

    @Value("${hmdp.swagger.package:}")
    private String packageName;
}
