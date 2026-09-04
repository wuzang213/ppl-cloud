package com.hmdp.blog.service.impl;


import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.hmdp.api.client.UserClient;
import com.hmdp.blog.constants.BlogConstants;
import com.hmdp.blog.domain.Follow;
import com.hmdp.blog.mapper.FollowMapper;
import com.hmdp.blog.service.IFollowService;
import com.hmdp.common.constants.MqConstants;
import com.hmdp.common.constants.RedisConstants;
import com.hmdp.common.domain.NoticeMessage;
import com.hmdp.common.domain.Result;
import com.hmdp.common.utils.RabbitMqHelper;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserClient userClient;

    @Resource
    private RabbitMqHelper rabbitMqHelper;


    //取消关注service
    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 3.判断
        return Result.ok(count > 0);
    }

    //关注service
    @Override
    @Transactional
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FOLLOW_KEY + userId;
        // 1.判断到底是关注还是取关
        if (Boolean.TRUE.equals(isFollow)) {
            // 2.关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);
            afterCommit(() -> {
                // 把关注用户的id，放入redis的set集合 sadd userId followerUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
                if (!followUserId.equals(userId)) {
                    rabbitMqHelper.sendMessageWithConfirm(
                            MqConstants.NOTICE_DIRECT_EXCHANGE,
                            MqConstants.NOTICE_ROUTING_KEY,
                            new NoticeMessage(followUserId, BlogConstants.NOTICE_TYPE_FOLLOW,
                                    BlogConstants.NOTICE_FOLLOW_CONTENT, userId),
                            MqConstants.MQ_RETRY_TIMES);
                }
            });
        } else {
            // 3.取关，删除
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            afterCommit(() -> stringRedisTemplate.opsForSet().remove(key, followUserId.toString()));
        }
        return Result.ok();
    }

    @Override
    public Result followCommons(Long id) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FOLLOW_KEY + userId;
        // 2.求交集
        String key2 = RedisConstants.FOLLOW_KEY + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.查询用户
        Result<List<UserDTO>> result = userClient.listUserByIds(ids);
        if (result == null || !Boolean.TRUE.equals(result.getSuccess()) || result.getData() == null) {
            return Result.ok(Collections.emptyList());
        }
        List<UserDTO> users = result.getData();

        return Result.ok(users);
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
