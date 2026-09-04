package com.hmdp.shop.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;

import com.hmdp.common.cache.CacheClient;
import com.hmdp.common.cache.SingleFlight;
import com.hmdp.common.count.ViewCounter;
import org.redisson.api.RBloomFilter;
import com.hmdp.common.constants.SystemConstants;
import com.hmdp.common.domain.Result;
import com.hmdp.common.exception.BadRequestException;
import com.hmdp.shop.constants.ShopConstants;
import com.hmdp.shop.domain.Shop;
import com.hmdp.shop.domain.ShopDTO;
import com.hmdp.shop.mapper.ShopMapper;
import com.hmdp.shop.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.common.constants.RedisConstants.*;

@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private SingleFlight singleFlight;

    @Resource
    private RBloomFilter<String> shopBloomFilter;

    @Resource
    private ViewCounter viewCounter;

    @Resource
    private Cache<Long, Shop> shopCache;

    @Override
    public Result queryById(Long id) {
        // 增加浏览量，请求路径只做一次内存入队，不碰 Redis
        viewCounter.asyncIncr(VIEW_SHOP_KEY, id);
        // 1. 查Caffeine本地缓存
        Shop shop = shopCache.getIfPresent(id);
        if (shop != null) {
            // log.info("从Caffeine缓存中获取了数据{}", shop);

            return Result.ok(shop);
        }
        // 2.只有本地缓存没命中，才访问Redis布隆过滤器
        if (!shopBloomFilter.contains(id.toString())) {
            throw new BadRequestException(ShopConstants.SHOP_NOT_FOUND);
        }

        // 3. 查Redis+MySQL，single-flight合并热点回源
        shop = singleFlight.run(CACHE_SHOP_KEY + id, () -> cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES));
        if (shop == null) {
            throw new BadRequestException(ShopConstants.SHOP_NOT_FOUND);
        }
        // 4. 回填Caffeine
        shopCache.put(id, shop);

        return Result.ok(shop);

    }

    @Override
    @Transactional
    public Result update(ShopDTO dto) {
        Shop shop = BeanUtil.copyProperties(dto, Shop.class);
        Long id = shop.getId();
        if(id == null){
            throw new BadRequestException(ShopConstants.SHOP_ID_REQUIRED);
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 事务提交后删缓存、更新 GEO
        afterCommit(() -> {
            stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
            stringRedisTemplate.opsForGeo().add(
                    SHOP_GEO_KEY + shop.getTypeId(),
                    new Point(shop.getX(), shop.getY()),
                    shop.getId().toString());
            shopBloomFilter.add(id.toString());
        });
        return Result.ok();
    }

    @Override
    @Transactional
    public Result saveShop(ShopDTO dto) {
        Shop shop = BeanUtil.copyProperties(dto, Shop.class);
        save(shop);
        afterCommit(() -> {
            stringRedisTemplate.opsForGeo().add(
                    SHOP_GEO_KEY + shop.getTypeId(),
                    new Point(shop.getX(), shop.getY()),
                    shop.getId().toString());
            shopBloomFilter.add(shop.getId().toString());
        });
        return Result.ok(shop.getId());
    }

    @Override
    public Result queryShopByName(String name, Integer current) {
        Page<Shop> page = query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y, Integer dis) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(dis),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }

    private void afterCommit(Runnable runnable) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runnable.run();
            }
        });
    }
}
