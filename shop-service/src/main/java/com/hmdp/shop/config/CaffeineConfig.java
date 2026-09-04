package com.hmdp.shop.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;


import com.hmdp.shop.domain.Shop;
import com.hmdp.shop.domain.ShopType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class CaffeineConfig {
    /**
     * 店铺缓存
     * @return
     */
    @Bean
    public Cache<Long, Shop> shopCache(){
        return Caffeine.newBuilder()
                .initialCapacity(100)// 初始容量
                .maximumSize(10_000)// 最大容量
                .expireAfterWrite(30, TimeUnit.MINUTES)// 30分钟后过期
                .build();
    }

    /**
     * 店铺类型缓存
     * @return
     */
    @Bean
    public Cache<String, List<ShopType>> shopTypeListCache(){
        return Caffeine.newBuilder()
                .initialCapacity(10)
                .maximumSize(100)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .build();
    }
}