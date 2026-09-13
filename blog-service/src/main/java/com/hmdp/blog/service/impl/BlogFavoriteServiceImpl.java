package com.hmdp.blog.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.blog.domain.BlogFavorite;
import com.hmdp.blog.mapper.BlogFavoriteMapper;
import com.hmdp.blog.service.IBlogFavoriteService;
import com.hmdp.common.constants.RedisConstants;
import com.hmdp.common.constants.SystemConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.outbox.OutboxWriter;
import com.hmdp.common.utils.UserHolder;
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
public class BlogFavoriteServiceImpl extends ServiceImpl<BlogFavoriteMapper, BlogFavorite>
        implements IBlogFavoriteService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OutboxWriter outboxWriter;

    @Override
    @Transactional
    public Result favorite(Long blogId, Boolean favorite) {
        UserDTO user = UserHolder.getUser();
        boolean fav = Boolean.TRUE.equals(favorite);
        if (fav) {
            BlogFavorite favoriteRecord = new BlogFavorite();
            favoriteRecord.setUserId(user.getId());
            favoriteRecord.setBlogId(blogId);
            favoriteRecord.setCreateTime(LocalDateTime.now());
            try {
                save(favoriteRecord);
            } catch (DuplicateKeyException e) {
                log.debug("already favorited, blogId={}", blogId);
            }
        } else {
            remove(new QueryWrapper<BlogFavorite>()
                    .eq("user_id", user.getId())
                    .eq("blog_id", blogId));
        }
        // 收藏关系变更写入 outbox：Redis 收藏集合由消费者维护，失败可对账重试
        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getId());
        data.put("blogId", blogId);
        data.put("favorite", fav);
        CacheSyncMessage msg = new CacheSyncMessage("BLOG_FAVORITE", blogId, null, "BLOG_FAVORITE_CHANGED");
        msg.setData(data);
        outboxWriter.write("BLOG_FAVORITE", blogId, "BLOG_FAVORITE_CHANGED", msg);
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
}
