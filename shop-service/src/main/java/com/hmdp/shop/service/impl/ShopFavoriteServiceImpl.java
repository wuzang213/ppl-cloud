package com.hmdp.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.common.constants.RedisConstants;
import com.hmdp.common.constants.SystemConstants;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.utils.UserHolder;
import com.hmdp.shop.domain.ShopFavorite;
import com.hmdp.shop.mapper.ShopFavoriteMapper;
import com.hmdp.shop.service.IShopFavoriteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Slf4j
@Service
public class ShopFavoriteServiceImpl extends ServiceImpl<ShopFavoriteMapper, ShopFavorite>
        implements IShopFavoriteService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public Result favorite(Long shopId, Boolean favorite) {
        UserDTO user = UserHolder.getUser();
        String key = RedisConstants.FAVORITE_SHOP_KEY + user.getId();
        if (Boolean.TRUE.equals(favorite)) {
            ShopFavorite record = new ShopFavorite();
            record.setUserId(user.getId());
            record.setShopId(shopId);
            record.setCreateTime(LocalDateTime.now());
            try {
                save(record);
            } catch (DuplicateKeyException e) {
                log.debug("already favorited, shopId={}", shopId);
            }
            afterCommit(() -> stringRedisTemplate.opsForSet().add(key, shopId.toString()));
        } else {
            remove(new QueryWrapper<ShopFavorite>()
                    .eq("user_id", user.getId())
                    .eq("shop_id", shopId));
            afterCommit(() -> stringRedisTemplate.opsForSet().remove(key, shopId.toString()));
        }
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

    private void afterCommit(Runnable runnable) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runnable.run();
            }
        });
    }
}