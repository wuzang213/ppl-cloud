package com.hmdp.blog.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;

import com.hmdp.api.client.UserClient;
import com.hmdp.blog.domain.Blog;
import com.hmdp.blog.constants.BlogConstants;
import com.hmdp.blog.domain.BlogDTO;
import com.hmdp.blog.domain.Follow;
import com.hmdp.blog.domain.BlogLikeMessage;
import com.hmdp.blog.mapper.BlogMapper;
import com.hmdp.blog.service.IBlogService;
import com.hmdp.blog.service.IFollowService;
import com.hmdp.common.constants.SystemConstants;
import com.hmdp.common.constants.MqConstants;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.domain.ScrollResult;
import com.hmdp.common.count.ViewCounter;
import com.hmdp.common.outbox.OutboxWriter;
import org.springframework.data.redis.RedisConnectionFailureException;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.exception.BadRequestException;
import com.hmdp.common.exception.BizIllegalException;
import com.hmdp.common.utils.UserHolder;
import com.hmdp.common.utils.RabbitMqHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.common.constants.RedisConstants.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
//    @Resource
//    private IUserService userService;

    private final UserClient userClient;
    @Resource
    private RabbitMqHelper rabbitMqHelper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Resource
    private Cache<Long, Blog> blogCache;
    @Resource
    private Cache<Integer, List<Blog>> blogHotCache;

    @Resource
    private ViewCounter viewCounter;

    @Resource
    private OutboxWriter outboxWriter;

    @Override
    public Result queryBlogById(Long id) {
        // 请求路径只做一次内存入队，不碰 Redis
        viewCounter.asyncIncr(VIEW_BLOG_KEY, id);
        // 1. 查Caffeine本地缓存
        Blog blog = blogCache.getIfPresent(id);
        if (blog != null) {
            Blog result = copyBlog(blog);
            queryBlogUser(result);
            isBlogLiked(result);
            return Result.ok(result);
        }
        // 2. 查Redis
        String key = CACHE_BLOG_KEY + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            blog = JSONUtil.toBean(json, Blog.class);
            Blog cached = copyBlog(blog);
            blogCache.put(id, cached);
            queryBlogUser(blog);
            isBlogLiked(blog);
            return Result.ok(blog);
        }
        // 3. 查MySQL
        blog = getById(id);
        if (blog == null) {
            throw new BadRequestException(BlogConstants.BLOG_NOT_FOUND);
        }
        queryBlogUser(blog);
        Blog cached = copyBlog(blog);
        cached.setIsLike(null);
        // 4. 回填Redis + Caffeine
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(cached), CACHE_BLOG_TTL, TimeUnit.MINUTES);
        blogCache.put(id, cached);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    @Transactional
    public Result likeBlog(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            throw new BadRequestException(BlogConstants.BLOG_NOT_FOUND);
        }
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        boolean liked = stringRedisTemplate.opsForZSet().score(key, userId.toString()) == null;
        boolean isSuccess;
        if (liked) {
            isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
        } else {
            isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
        }
        if (isSuccess) {
            BlogLikeMessage message = new BlogLikeMessage(blog.getUserId(), id, userId, liked);
            // afterCommit：只有数据库事务真正提交成功，才发送MQ消息
            afterCommit(() -> rabbitMqHelper.sendMessageWithConfirm(
                    MqConstants.BLOG_LIKE_DIRECT_EXCHANGE,
                    MqConstants.BLOG_LIKE_ROUTING_KEY,
                    message,
                    MqConstants.MQ_RETRY_TIMES));
        }
        return Result.ok();
    }

    /**
     * 只有数据库事务真正提交成功，才执行runnable
     * @param
     */
    private Blog copyBlog(Blog blog) {
        return JSONUtil.toBean(JSONUtil.toJsonStr(blog), Blog.class);
    }

    private void afterCommit(Runnable runnable) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runnable.run();
            }
        });
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 1. 查Caffeine本地缓存
        List<Blog> records = blogHotCache.getIfPresent(current);
        if (records != null) {
            log.info("从Caffeine缓存中获取数据");
            List<Blog> result = records.stream().map(this::copyBlog).collect(Collectors.toList());
            result.forEach(this::isBlogLiked);
            return Result.ok(result);
        }
        // 2. 查Redis
        String key = CACHE_BLOG_HOT_KEY + current;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            records = JSONUtil.toList(json, Blog.class);
            List<Blog> cached = records.stream().map(this::copyBlog).collect(Collectors.toList());
            blogHotCache.put(current, cached);
            cached.forEach(this::isBlogLiked);
            return Result.ok(cached);
        }
        // 3. 查MySQL
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        records = page.getRecords();
        queryBlogUser(records);
        // 4. 回填Redis + Caffeine
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(records), CACHE_BLOG_HOT_TTL, TimeUnit.MINUTES);
        blogHotCache.put(current, records);
        return Result.ok(records);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());

        // 3. Feign远程调用user服务批量接口
        Result<List<UserDTO>> result = userClient.listUserByIds(ids);
        if (result == null || !Boolean.TRUE.equals(result.getSuccess()) || result.getData() == null) {
            return Result.ok(Collections.emptyList());
        }
        List<UserDTO> userDTOS = result.getData();

        // 内存排序，替代数据库 ORDER BY FIELD
        List<UserDTO> sortedList = ids.stream()
                .map(uid -> userDTOS.stream()
                        .filter(dto -> uid.equals(dto.getId()))
                        .findFirst().orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(sortedList);
    }

    @Override
    @Transactional
    public Result saveBlog(BlogDTO dto) {
        Blog blog = BeanUtil.copyProperties(dto, Blog.class);
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店笔记
        boolean isSuccess = save(blog);
        if(!isSuccess){
            throw new BizIllegalException(BlogConstants.BLOG_SAVE_FAILED);
        }
        // 3.业务与outbox同事务写入，保证业务入库成功事件不丢
        outboxWriter.write("BLOG", blog.getId(), "BLOG_PUBLISHED",
                new CacheSyncMessage("BLOG", blog.getId(), null));
        // 4.粉丝量大走拉模式，普通用户推送到粉丝收件箱
        boolean bigV = followService.query()
                .eq("follow_user_id", user.getId())
                .count() >= BlogConstants.FEED_FAN_THRESHOLD;
        afterCommit(() -> {
            long timestamp = System.currentTimeMillis();
            if (bigV) {
                stringRedisTemplate.opsForZSet().add(
                        FEED_PULL_KEY + user.getId(),
                        blog.getId().toString(),
                        timestamp);
                return;
            }
            List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
            for (Follow follow : follows) {
                stringRedisTemplate.opsForZSet().add(
                        FEED_KEY + follow.getUserId(),
                        blog.getId().toString(),
                        timestamp);
            }
        });
        // 5.返回id
        return Result.ok(blog.getId());
    }

        @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        try {
            String pushKey = FEED_KEY + userId;
            TreeSet<ZSetOperations.TypedTuple<String>> merged = new TreeSet<>(
                    Comparator.comparingLong((ZSetOperations.TypedTuple<String> t) -> t.getScore().longValue())
                            .reversed()
                            .thenComparing(ZSetOperations.TypedTuple::getValue));
            addFeedTuples(merged, stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(
                    pushKey, 0, max, offset, SystemConstants.DEFAULT_PAGE_SIZE));

            List<Follow> follows = followService.query().eq("user_id", userId).list();
            for (Follow follow : follows) {
                addFeedTuples(merged, stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(
                        FEED_PULL_KEY + follow.getFollowUserId(), 0, max, 0, SystemConstants.DEFAULT_PAGE_SIZE));
            }
            if (merged.isEmpty()) {
                return Result.ok();
            }
            List<ZSetOperations.TypedTuple<String>> page = merged.stream()
                    .skip(offset == null ? 0 : offset)
                    .limit(SystemConstants.DEFAULT_PAGE_SIZE)
                    .collect(Collectors.toList());
            if (page.isEmpty()) {
                return Result.ok();
            }
            List<Long> ids = new ArrayList<>(page.size());
            long minTime = page.get(page.size() - 1).getScore().longValue();
            int os = 0;
            for (ZSetOperations.TypedTuple<String> tuple : page) {
                ids.add(Long.valueOf(tuple.getValue()));
                if (tuple.getScore().longValue() == minTime) {
                    os++;
                }
            }
            String idStr = StrUtil.join(",", ids);
            List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
            queryBlogUser(blogs);
            for (Blog blog : blogs) {
                isBlogLiked(blog);
            }
            ScrollResult r = new ScrollResult();
            r.setList(blogs);
            r.setOffset(os);
            r.setMinTime(minTime);
            return Result.ok(r);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis不可用，Feed流降级为纯拉模式，userId={}", userId);
            return queryBlogOfFollowFromDb(userId, max, offset);
        }
    }

    private void addFeedTuples(Set<ZSetOperations.TypedTuple<String>> target,
                               Set<ZSetOperations.TypedTuple<String>> tuples) {
        if (tuples != null) {
            target.addAll(tuples);
        }
    }

    private Result queryBlogOfFollowFromDb(Long userId, Long max, Integer offset) {
        LocalDateTime before = max == null ? LocalDateTime.now()
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(max), ZoneId.systemDefault());
        Page<Blog> page = query()
                .lt("create_time", before)
                .inSql("user_id", "select follow_user_id from tb_follow where user_id = " + userId)
                .orderByDesc("create_time")
                .page(new Page<>(1, SystemConstants.DEFAULT_PAGE_SIZE));
        List<Blog> blogs = page.getRecords();
        if (blogs.isEmpty()) {
            return Result.ok();
        }
        queryBlogUser(blogs);
        for (Blog blog : blogs) {
            isBlogLiked(blog);
        }
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(blogs.get(blogs.size() - 1).getCreateTime()
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        r.setOffset(offset == null ? 1 : offset + 1);
        return Result.ok(r);
    }
@Override
    public Result queryMyBlog(Integer current) {
        UserDTO user = UserHolder.getUser();
        Page<Blog> page = query()
                .eq("user_id", user.getId())
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @Override
    public Result queryBlogByUserId(Long id, Integer current) {
        Page<Blog> page = query()
                .eq("user_id", id)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @Override
    public Result queryBlogByShopId(Long shopId, Integer current) {
        Page<Blog> page = query()
                .eq("shop_id", shopId)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        queryBlogUser(records);
        records.forEach(this::isBlogLiked);
        return Result.ok(records);
    }

    @Override
    public Result queryBlogPage(Integer current, Integer size) {
        Page<Blog> page = query()
                .orderByDesc("create_time")
                .page(new Page<>(current, size));
        return Result.ok(page.getRecords());
    }

    private void queryBlogUser(Blog blog) {
        queryBlogUser(Collections.singletonList(blog));
    }

    /**
     * 批量查询blog用户
     * @param blogs
     */
    private void queryBlogUser(List<Blog> blogs) {
        if (blogs == null || blogs.isEmpty()) {
            return;
        }
        List<Long> userIds = blogs.stream()
                .map(Blog::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (userIds.isEmpty()) {
            return;
        }
        Result<List<UserDTO>> result = userClient.listUserByIds(userIds);
        if (result == null || !Boolean.TRUE.equals(result.getSuccess()) || result.getData() == null) {
            return;
        }

        Map<Long, UserDTO> userMap = result.getData().stream()
                .filter(dto -> dto != null && dto.getId() != null)
                .collect(Collectors.toMap(UserDTO::getId, Function.identity()));
        for (Blog blog : blogs) {
            UserDTO dto = userMap.get(blog.getUserId());
            if (dto != null) {
                blog.setName(dto.getNickName());
                blog.setIcon(dto.getIcon());
            }
        }
    }

    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
}
