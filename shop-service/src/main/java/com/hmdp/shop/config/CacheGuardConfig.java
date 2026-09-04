package com.hmdp.shop.config;

import com.hmdp.shop.mapper.ShopMapper;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;

/**
 * 店铺 ID 布隆过滤器，拦截不存在的店铺 ID，缓解缓存穿透。
 */
@Configuration
public class CacheGuardConfig {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ShopMapper shopMapper;

    @Bean
    public RBloomFilter<String> shopBloomFilter() {
        RBloomFilter<String> bloom = redissonClient.getBloomFilter("bloom:shop:id");
        if (bloom.tryInit(1_000_000L, 0.01D)) {
            shopMapper.selectList(null)
                    .forEach(shop -> bloom.add(shop.getId().toString()));
        }
        return bloom;
    }
}
