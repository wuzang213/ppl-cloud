package com.hmdp.shop.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;

import com.hmdp.common.cache.CacheClient;
import com.hmdp.common.cache.CacheProperties;
import com.hmdp.common.cache.SingleFlight;
import com.hmdp.common.count.ViewCounter;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.outbox.OutboxWriter;
import com.hmdp.common.search.ShopSearchDocDTO;
import org.redisson.api.RBloomFilter;
import com.hmdp.common.constants.SystemConstants;
import com.hmdp.common.domain.Result;
import com.hmdp.common.exception.BadRequestException;
import com.hmdp.shop.constants.ShopConstants;
import com.hmdp.shop.domain.Shop;
import com.hmdp.shop.domain.ShopDTO;
import com.hmdp.shop.domain.ShopType;
import com.hmdp.shop.mapper.ShopMapper;
import com.hmdp.shop.service.IShopService;
import com.hmdp.shop.service.IShopTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    // 压测对照开关：hmdp.cache.enabled=false 时跳过全部缓存，直达 MySQL
    @Resource
    private CacheProperties cacheProperties;

    // Shop 接入 Outbox，统一缓存同步可靠性等级
    @Resource
    private OutboxWriter outboxWriter;

    // 搜索文档需要冗余分类名（typeName 参与检索：搜"火锅"要能出店名不含"火锅"的火锅店）
    @Resource
    private IShopTypeService shopTypeService;

    @Override
    public Result queryById(Long id) {
        // 增加浏览量，请求路径只做一次内存入队，不碰 Redis
        // 注意：改造前后均存在，其开销不计入缓存改造收益，故不受 cache.enabled 控制
        viewCounter.asyncIncr(VIEW_SHOP_KEY, id);
        Shop shop = loadShopById(id, true);
        if (shop == null) {
            throw new BadRequestException(ShopConstants.SHOP_NOT_FOUND);
        }
        return Result.ok(shop);
    }

    /** 只读加载店铺（缓存链路 + single-flight + 空值防穿透），无任何副作用；不存在返回 {@code null}。
     * @param checkBloom 是否走布隆过滤器。公网详情走（挡穿透），搜索索引反查不走 —— 布隆是异步写入的，
     * 新店可能还没进过滤器，走布隆会把「刚建好的店」误判成「不存在」，进而误删 ES 文档。 */
    private Shop loadShopById(Long id, boolean checkBloom) {
        // 压测对照开关：直达 MySQL（模拟改造前）
        if (!cacheProperties.isEnabled()) {
            return getById(id);
        }
        // 1. 查Caffeine本地缓存
        Shop shop = shopCache.getIfPresent(id);
        if (shop != null) {
            return shop;
        }
        // 2.只有本地缓存没命中，才访问Redis布隆过滤器
        if (checkBloom && !shopBloomFilter.contains(id.toString())) {
            return null;
        }
        // 3. 查Redis+MySQL，single-flight合并热点回源
        shop = singleFlight.run(CACHE_SHOP_KEY + id, () -> cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES));
        if (shop == null) {
            return null;
        }
        // 4. 回填Caffeine
        shopCache.put(id, shop);
        return shop;
    }

    @Override
    public Result queryShopSearchDoc(Long id) {
        Shop shop = loadShopById(id, false);
        if (shop == null) {
            // 不存在 = 正常语义（调用方据此删 ES 文档）
            return Result.ok();
        }
        return Result.ok(toSearchDoc(shop, shopTypeName(shop.getTypeId())));
    }

    @Override
    public Result queryShopSearchDocPage(Integer current, Integer size) {
        Page<Shop> page = query()
                .orderByAsc("id")
                .page(new Page<>(current, size));
        List<Shop> records = page.getRecords();
        if (records.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 分类只有个位数，一次 list() 建映射即可，避免 N 次查库
        Map<Long, String> typeNameMap = shopTypeService.list().stream()
                .filter(t -> t.getId() != null)
                .collect(Collectors.toMap(ShopType::getId, ShopType::getName, (a, b) -> a));
        List<ShopSearchDocDTO> docs = records.stream()
                .map(s -> toSearchDoc(s, typeNameMap.get(s.getTypeId())))
                .collect(Collectors.toList());
        return Result.ok(docs);
    }

    private String shopTypeName(Long typeId) {
        if (typeId == null) {
            return null;
        }
        ShopType type = shopTypeService.getById(typeId);
        return type == null ? null : type.getName();
    }

    /** Shop -> 搜索文档。全量与增量两条路径都走这里，保证口径不分叉。{@code typeName} 是店铺索引里唯一有实际
     * 检索价值的冗余字段：店名往往不含品类词（如「蜀大侠」），把分类名纳入检索字段后，搜「火锅」才能出这些店。 */
    private ShopSearchDocDTO toSearchDoc(Shop shop, String typeName) {
        ShopSearchDocDTO doc = new ShopSearchDocDTO();
        doc.setId(shop.getId());
        doc.setName(shop.getName());
        doc.setTypeId(shop.getTypeId());
        doc.setTypeName(typeName);
        doc.setImages(shop.getImages());
        doc.setArea(shop.getArea());
        doc.setAddress(shop.getAddress());
        doc.setX(shop.getX());
        doc.setY(shop.getY());
        doc.setAvgPrice(shop.getAvgPrice());
        doc.setSold(shop.getSold());
        doc.setComments(shop.getComments());
        doc.setViewCount(shop.getViewCount());
        doc.setScore(shop.getScore());
        doc.setOpenHours(shop.getOpenHours());
        doc.setCreateTime(shop.getCreateTime());
        doc.setUpdateTime(shop.getUpdateTime());
        return doc;
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
        // 2. 写 outbox：删缓存 + 更新 GEO + 布隆统一由 ShopCacheListener 执行，
        //    经 Canal + MQ 投递，有记录可对账重试，不再依赖请求线程内的 afterCommit
        outboxWriter.write("SHOP", id, "SHOP_UPDATED", shopSyncMessage(shop, "SHOP_UPDATED"));
        return Result.ok();
    }

    @Override
    @Transactional
    public Result saveShop(ShopDTO dto) {
        Shop shop = BeanUtil.copyProperties(dto, Shop.class);
        save(shop);
        // 写 outbox：GEO + 布隆同样交给 ShopCacheListener 执行
        outboxWriter.write("SHOP", shop.getId(), "SHOP_CREATED", shopSyncMessage(shop, "SHOP_CREATED"));
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

    @Override
    @Transactional
    public Result deleteShop(Long id) {
        removeById(id);
        // 写 outbox：删缓存 + 布隆（布隆不支持删除，保持原有 add 语义）交给 ShopCacheListener
        outboxWriter.write("SHOP", id, "SHOP_DELETED", new CacheSyncMessage("SHOP", id, null, "SHOP_DELETED"));
        return Result.ok();
    }

    /** 构造 SHOP 缓存同步消息：把 GEO 更新所需的坐标、类型一并带入，由 ShopCacheListener 消费时执行
     * Redis 缓存删除、GEO 更新与布隆写入。 */
    private CacheSyncMessage shopSyncMessage(Shop shop, String eventType) {
        Map<String, Object> data = new HashMap<>();
        data.put("typeId", shop.getTypeId());
        data.put("x", shop.getX());
        data.put("y", shop.getY());
        CacheSyncMessage msg = new CacheSyncMessage("SHOP", shop.getId(), null, eventType);
        msg.setData(data);
        return msg;
    }
}
