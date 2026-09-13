package com.hmdp.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.common.constants.RedisConstants;
import com.hmdp.common.constants.SystemConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.outbox.OutboxWriter;
import com.hmdp.common.utils.UserHolder;
import com.hmdp.shop.domain.ShopFavorite;
import com.hmdp.shop.mapper.ShopFavoriteMapper;
import com.hmdp.shop.service.IShopFavoriteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class ShopFavoriteServiceImpl extends ServiceImpl<ShopFavoriteMapper, ShopFavorite>
        implements IShopFavoriteService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OutboxWriter outboxWriter;

    @Override
    @Transactional
    public Result favorite(Long shopId, Boolean favorite) {
        UserDTO user = UserHolder.getUser();
        boolean fav = Boolean.TRUE.equals(favorite);
        if (fav) {
            ShopFavorite record = new ShopFavorite();
            record.setUserId(user.getId());
            record.setShopId(shopId);
            record.setCreateTime(LocalDateTime.now());
            try {
                save(record);
            } catch (DuplicateKeyException e) {
                log.debug("already favorited, shopId={}", shopId);
            }
        } else {
            remove(new QueryWrapper<ShopFavorite>()
                    .eq("user_id", user.getId())
                    .eq("shop_id", shopId));
        }
        // 收藏关系变更写入 outbox：Redis 收藏集合由消费者维护，失败可对账重试
        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getId());
        data.put("shopId", shopId);
        data.put("favorite", fav);
        CacheSyncMessage msg = new CacheSyncMessage("SHOP_FAVORITE", shopId, null, "SHOP_FAVORITE_CHANGED");
        msg.setData(data);
        outboxWriter.write("SHOP_FAVORITE", shopId, "SHOP_FAVORITE_CHANGED", msg);
        return Result.ok();
    }

    @Override
    public Result myFavorites(Integer current) {
        UserDTO user = UserHolder.getUser();
        Page<ShopFavorite> page = query()
                .eq("user_id", user.getId())
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @Override
    public Result isFavorite(Long shopId) {
        UserDTO user = UserHolder.getUser();
        String key = RedisConstants.FAVORITE_SHOP_KEY + user.getId();
        return Result.ok(Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(key, shopId.toString())));
    }
}
