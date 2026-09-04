package com.hmdp.blog.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.blog.domain.BlogFavorite;
import com.hmdp.blog.mapper.BlogFavoriteMapper;
import com.hmdp.blog.service.IBlogFavoriteService;
import com.hmdp.common.constants.RedisConstants;
import com.hmdp.common.constants.SystemConstants;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.utils.UserHolder;
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
public class BlogFavoriteServiceImpl extends ServiceImpl<BlogFavoriteMapper, BlogFavorite>
        implements IBlogFavoriteService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public Result favorite(Long blogId, Boolean favorite) {
        UserDTO user = UserHolder.getUser();
        String key = RedisConstants.FAVORITE_BLOG_KEY + user.getId();
        if (Boolean.TRUE.equals(favorite)) {
            BlogFavorite favoriteRecord = new BlogFavorite();
            favoriteRecord.setUserId(user.getId());
            favoriteRecord.setBlogId(blogId);
            favoriteRecord.setCreateTime(LocalDateTime.now());
            try {
                save(favoriteRecord);
            } catch (DuplicateKeyException e) {
                log.debug("already favorited, blogId={}", blogId);
            }
            afterCommit(() -> stringRedisTemplate.opsForSet().add(key, blogId.toString()));
        } else {
            remove(new QueryWrapper<BlogFavorite>()
                    .eq("user_id", user.getId())
                    .eq("blog_id", blogId));
            afterCommit(() -> stringRedisTemplate.opsForSet().remove(key, blogId.toString()));
        }
        return Result.ok();
    }

    @Override
    public Result myFavorites(Integer current) {
        UserDTO user = UserHolder.getUser();
        Page<BlogFavorite> page = query()
                .eq("user_id", user.getId())
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @Override
    public Result isFavorite(Long blogId) {
        UserDTO user = UserHolder.getUser();
        String key = RedisConstants.FAVORITE_BLOG_KEY + user.getId();
        return Result.ok(Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(key, blogId.toString())));
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