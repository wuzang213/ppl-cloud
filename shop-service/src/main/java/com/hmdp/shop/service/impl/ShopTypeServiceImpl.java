package com.hmdp.shop.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;

import com.hmdp.common.domain.Result;
import com.hmdp.common.exception.BadRequestException;
import com.hmdp.shop.constants.ShopConstants;
import com.hmdp.shop.domain.ShopType;
import com.hmdp.shop.mapper.ShopTypeMapper;
import com.hmdp.shop.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.common.constants.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.common.constants.RedisConstants.CACHE_SHOP_TYPE_TTL;


@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Cache<String, List<ShopType>> shopTypeListCache;

    @Override
    public Result queryTypeList() {
        String cacheKey = "all";
        // 1. 查Caffeine本地缓存
        List<ShopType> typeList = shopTypeListCache.getIfPresent(cacheKey);
        if (typeList != null) {
            return Result.ok(typeList);
        }
        // 2. 从redis查询商铺缓存
        String key = CACHE_SHOP_TYPE_KEY;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 3. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 4. 存在，回填Caffeine并直接返回
            typeList = JSONUtil.toList(shopJson, ShopType.class);
            shopTypeListCache.put(cacheKey, typeList);
            return Result.ok(typeList);
        }
        // 5. 不存在，查询数据库（按sort升序）
        typeList = query().orderByAsc("sort").list();
        // 6. 判断是否为空
        if (typeList == null || typeList.isEmpty()) {
            throw new BadRequestException(ShopConstants.SHOP_TYPE_NOT_FOUND);
        }
        // 7. 存在，写入Redis并回填Caffeine
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList), CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        shopTypeListCache.put(cacheKey, typeList);
        // 8. 返回
        return Result.ok(typeList);
    }
}
