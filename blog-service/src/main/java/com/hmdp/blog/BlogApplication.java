package com.hmdp.blog;


import com.hmdp.api.config.DefaultFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.kafka.annotation.EnableKafka;

@EnableFeignClients(basePackages = "com.hmdp.api.client", defaultConfiguration = DefaultFeignConfig.class)
@MapperScan("com.hmdp.blog.mapper")
@EnableKafka
@SpringBootApplication
public class BlogApplication {
    public static void main(String[] args) {
        SpringApplication.run(BlogApplication.class, args);
    }
}