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
import com.hmdp.common.cache.CacheClient;
import com.hmdp.common.cache.SingleFlight;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.domain.NoticeMessage;
import com.hmdp.common.domain.ScrollResult;
import com.hmdp.common.count.ViewCounter;
import com.hmdp.common.outbox.OutboxWriter;
import org.springframework.dao.DataAccessException;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.exception.BadRequestException;
import com.hmdp.common.exception.BizIllegalException;
import com.hmdp.common.utils.UserHolder;
import com.hmdp.common.utils.AfterCommitExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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

    // Blog 缓存查询加 single-flight 防击穿
    @Resource
    private SingleFlight singleFlight;

    @Resource
    private CacheClient cacheClient;

    // Feed 并行拉取专用线程池
    private static final AtomicInteger FEED_SEQ = new AtomicInteger(0);
    private static final ExecutorService FEED_PARALLEL_EXECUTOR = new ThreadPoolExecutor(
            4, 32, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(512),
            r -> {
                Thread t = new Thread(r, "feed-parallel-" + FEED_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    @Override
    public Result queryBlogById(Long id) {
        // 请求路径只做一次内存入队，不碰 Redis
        viewCounter.asyncIncr(VIEW_BLOG_KEY, id);
        // 1. 查Caffeine本地缓存（只缓存博客基础DO，不含 isLike 等用户相关字段）
        Blog blog = blogCache.getIfPresent(id);
        if (blog == null) {
            // 2. Redis + MySQL 整体交给 queryWithPassThrough（自带随机 TTL + 空值防穿透），
            //    并用 single-flight 合并热点回源；不能只包 DB，否则并发请求依旧各自先打一次 Redis
            blog = singleFlight.run(CACHE_BLOG_KEY + id, () -> cacheClient.queryWithPassThrough(
                    CACHE_BLOG_KEY, id, Blog.class, this::getById, CACHE_BLOG_TTL, TimeUnit.MINUTES));
            if (blog == null) {
                throw new BadRequestException(BlogConstants.BLOG_NOT_FOUND);
            }
            // 3. 回填Caffeine
            blogCache.put(id, blog);
        }
        // 4. 拷贝一份再组装：queryBlogUser / isBlogLiked 是用户相关字段，直接改会污染共享缓存对象
        Blog result = copyBlog(blog);
        queryBlogUser(result);
        isBlogLiked(result);
        return Result.ok(result);
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
            BlogLikeMessage message = new BlogLikeMessage(id, userId, liked);
            // 1) 点赞关系的 Redis ZSet 同步：写 outbox 后经 Canal + MQ 投递到点赞交换机，
            //    消费端（BlogLikeListener）只负责维护 ZSet，有记录可对账重试
            outboxWriter.write("BLOG", id, "BLOG_LIKED",
                    MqConstants.BLOG_LIKE_DIRECT_EXCHANGE, MqConstants.BLOG_LIKE_ROUTING_KEY, message);
            // 2) 点赞通知：直接由本方法写第二条 outbox 事件投递到通知交换机，
            //    与 FollowServiceImpl.follow 的 FOLLOW_NOTICE 写法对称；
            //    不再让消费端二次转发，避免转发失败导致通知静默丢失
            if (liked && !blog.getUserId().equals(userId)) {
                NoticeMessage notice = new NoticeMessage(blog.getUserId(), BlogConstants.NOTICE_TYPE_LIKE,
                        BlogConstants.NOTICE_LIKE_CONTENT, id);
                outboxWriter.write("BLOG", id, "BLOG_NOTICE",
                        MqConstants.NOTICE_DIRECT_EXCHANGE, MqConstants.NOTICE_ROUTING_KEY, notice);
            }
        }
        return Result.ok();
    }

    /**
     * 拷贝一份博客副本，用户相关字段（isLike 等）只写在副本上，不污染缓存中的基础 DO
     */
    private Blog copyBlog(Blog blog) {
        return JSONUtil.toBean(JSONUtil.toJsonStr(blog), Blog.class);
    }

    /**
     * 只有数据库事务真正提交成功，才执行 runnable。
     * 仅保留给 {@link #saveBlog} 的弱一致 Feed 推送使用：Feed 属于尽力而为的可见性优化，
     * 即使推送失败，数据仍在 DB 中，可由读扩散/后续重建兜底。
     * 其余强一致副作用（缓存失效、GEO、布隆、通知等）一律走 outbox + MQ。
     */
    private void afterCommit(Runnable runnable) {
        AfterCommitExecutor.execute(runnable);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 1. 查Caffeine本地缓存（只缓存博客基础DO列表）
        List<Blog> records = blogHotCache.getIfPresent(current);
        if (records == null) {
            // 2. Redis + MySQL 整体交给 queryListWithPassThrough（自带随机 TTL + 空值防穿透），
            //    并用 single-flight 合并热点回源
            records = singleFlight.run(CACHE_BLOG_HOT_KEY + current, () -> cacheClient.queryListWithPassThrough(
                    CACHE_BLOG_HOT_KEY, current, Blog.class,
                    c -> query().orderByDesc("liked")
                            .page(new Page<>(c, SystemConstants.MAX_PAGE_SIZE)).getRecords(),
                    CACHE_BLOG_HOT_TTL, TimeUnit.MINUTES));
            // 3. 回填Caffeine
            blogHotCache.put(current, records);
        }
        // 4. 拷贝后组装用户信息与点赞状态，避免污染共享缓存对象
        List<Blog> result = records.stream().map(this::copyBlog).collect(Collectors.toList());
        queryBlogUser(result);
        // 批量一次 Pipeline 取点赞状态，避免 N 次 Redis 往返
        isBlogLiked(result);
        return Result.ok(result);
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
            // 用 Pipeline 批量推送，减少 RTT（1000 粉丝从 500ms 降到 ~5ms）
            List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] blogIdBytes = blog.getId().toString().getBytes(StandardCharsets.UTF_8);
                for (Follow follow : follows) {
                    byte[] key = (FEED_KEY + follow.getUserId()).getBytes(StandardCharsets.UTF_8);
                    connection.zAdd(key, timestamp, blogIdBytes);
                }
                return null;
            });
        });
        // 5.返回id
        return Result.ok(blog.getId());
    }

    // 补齐 Blog update/delete
    @Override
    @Transactional
    public Result updateBlog(BlogDTO dto) {
        Blog blog = BeanUtil.copyProperties(dto, Blog.class);
        if (blog.getId() == null) {
            throw new BadRequestException(BlogConstants.BLOG_NOT_FOUND);
        }
        // 校验作者权限
        Blog existing = getById(blog.getId());
        if (existing == null || !existing.getUserId().equals(UserHolder.getUser().getId())) {
            throw new BizIllegalException("无权修改他人笔记");
        }
        updateById(blog);
        // 缓存失效统一由 outbox -> BlogCacheListener 完成，无需再写 afterCommit
        outboxWriter.write("BLOG", blog.getId(), "BLOG_UPDATED",
                new CacheSyncMessage("BLOG", blog.getId(), null, "BLOG_UPDATED"));
        return Result.ok();
    }

    @Override
    @Transactional
    public Result deleteBlog(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            throw new BadRequestException(BlogConstants.BLOG_NOT_FOUND);
        }
        if (!blog.getUserId().equals(UserHolder.getUser().getId())) {
            throw new BizIllegalException("无权删除他人笔记");
        }
        removeById(id);
        // 缓存 + 点赞 ZSet 清理统一由 outbox -> BlogCacheListener 完成
        outboxWriter.write("BLOG", id, "BLOG_DELETED",
                new CacheSyncMessage("BLOG", id, null, "BLOG_DELETED"));
        return Result.ok();
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        try {
            int off = offset == null ? 0 : offset;
            // 关注列表按关注时间倒序取前 200（最近关注的通常更活跃）
            List<Follow> follows = followService.query()
                    .eq("user_id", userId)
                    .orderByDesc("create_time")
                    .last("LIMIT 200")
                    .list();

            // 7 天滑动窗口，避免扫描远古历史
            long minScore = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
            int pageSize = SystemConstants.DEFAULT_PAGE_SIZE;

            // 稳定排序（时间降序 + blogId 降序，保证翻页不抖动）
            TreeSet<ZSetOperations.TypedTuple<String>> merged = new TreeSet<>(
                    Comparator.comparingLong((ZSetOperations.TypedTuple<String> t) -> t.getScore().longValue())
                            .reversed()
                            .thenComparing(t -> Long.parseLong(t.getValue()), Comparator.reverseOrder()));

            // 自己推箱：offset 正确下推
            addFeedTuples(merged, stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(
                    FEED_KEY + userId, minScore, max, off, 50));

            // 并行拉取 200 个关注者发件箱，offset 正确下推（修复原 offset=0 bug）
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Follow follow : follows) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                                .reverseRangeByScoreWithScores(
                                        FEED_PULL_KEY + follow.getFollowUserId(), minScore, max, off, 50);
                        if (tuples != null && !tuples.isEmpty()) {
                            synchronized (merged) {
                                merged.addAll(tuples);
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Feed 并行拉取失败 followUserId={}", follow.getFollowUserId(), e);
                    }
                }, FEED_PARALLEL_EXECUTOR));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                log.debug("Feed 并行拉取部分超时，继续用已获取的数据");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.debug("Feed 并行拉取被中断");
            } catch (ExecutionException ee) {
                log.debug("Feed 并行拉取异常", ee);
            }

            if (merged.isEmpty()) {
                return Result.ok();
            }

            // 截断 2000 条防止内存膨胀
            List<ZSetOperations.TypedTuple<String>> sorted = new ArrayList<>(merged);
            if (sorted.size() > 2000) {
                sorted = sorted.subList(0, 2000);
            }

            // 截取 pageSize 条
            List<ZSetOperations.TypedTuple<String>> page = sorted.stream()
                    .limit(pageSize)
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
            // 批量一次 Pipeline 取点赞状态，避免 N 次 Redis 往返
            isBlogLiked(blogs);
            ScrollResult r = new ScrollResult();
            r.setList(blogs);
            r.setOffset(os);
            r.setMinTime(minTime);
            return Result.ok(r);
        } catch (DataAccessException e) {
            // catch 更宽的 DataAccessException，配合熔断快速失败
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
        // 批量一次 Pipeline 取点赞状态，避免 N 次 Redis 往返
        isBlogLiked(blogs);
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
        // 批量一次 Pipeline 取点赞状态，避免 N 次 Redis 往返
        isBlogLiked(records);
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
                .collect(Collectors.toMap(
                        UserDTO::getId,
                        Function.identity(),
                        (a, b) -> a   // 遇到重复 key，保留先出现的那个
                ));
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

    /**
     * 批量填充点赞状态：把 N 条 ZSCORE 用 Pipeline 打包成 1 次网络往返（1 RTT 而不是 N RTT）。
     */
    private void isBlogLiked(List<Blog> blogs) {
        if (blogs == null || blogs.isEmpty()) {
            return;
        }
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        List<Blog> targets = blogs.stream()
                .filter(Objects::nonNull)
                .filter(b -> b.getId() != null)
                .collect(Collectors.toList());
        if (targets.isEmpty()) {
            return;
        }
        String userId = user.getId().toString();
        // 2.Pipeline 打包全部 ZSCORE，一次性发出
        List<Object> scores = stringRedisTemplate.executePipelined(
                (RedisCallback<Object>) connection -> {
                    for (Blog blog : targets) {
                        connection.zSetCommands().zScore(
                                (BLOG_LIKED_KEY + blog.getId()).getBytes(StandardCharsets.UTF_8),
                                userId.getBytes(StandardCharsets.UTF_8));
                    }
                    return null;
                });
        // 3.按提交顺序回填结果（Pipeline 的结果顺序与命令提交顺序一致）
        for (int i = 0; i < targets.size(); i++) {
            Object score = i < scores.size() ? scores.get(i) : null;
            if (score instanceof Throwable) {
                // 单条命令失败不影响其它条目：按「未点赞」处理并留日志，避免整页查询失败
                log.warn("批量查询点赞状态部分失败，该条按未点赞处理: blogId={}",
                        targets.get(i).getId(), (Throwable) score);
                targets.get(i).setIsLike(false);
            } else {
                targets.get(i).setIsLike(score != null);
            }
        }
    }
}
