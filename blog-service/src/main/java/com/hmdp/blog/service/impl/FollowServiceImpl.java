package com.hmdp.blog.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.hmdp.api.client.UserClient;
import com.hmdp.blog.constants.BlogConstants;
import com.hmdp.blog.domain.Follow;
import com.hmdp.blog.mapper.FollowMapper;
import com.hmdp.blog.service.IFollowService;
import com.hmdp.common.constants.MqConstants;
import com.hmdp.common.constants.RedisConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.domain.NoticeMessage;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.outbox.OutboxWriter;
import com.hmdp.common.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserClient userClient;

    @Resource
    private OutboxWriter outboxWriter;

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
        // 2.判断到底是关注还是取关
        if (Boolean.TRUE.equals(isFollow)) {
            // 关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);
            // 关注关系变更写入 outbox：Redis 关注集合由消费者维护，失败可对账重试
            outboxWriter.write("FOLLOW", userId, "FOLLOW_CHANGED",
                    followChangeMessage(userId, followUserId, true));
            if (!followUserId.equals(userId)) {
                // 关注通知同样走 outbox，可靠投递到通知交换机
                NoticeMessage notice = new NoticeMessage(followUserId, BlogConstants.NOTICE_TYPE_FOLLOW,
                        BlogConstants.NOTICE_FOLLOW_CONTENT, userId);
                outboxWriter.write("FOLLOW", userId, "FOLLOW_NOTICE",
                        MqConstants.NOTICE_DIRECT_EXCHANGE, MqConstants.NOTICE_ROUTING_KEY, notice);
            }
        } else {
            // 取关，删除
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            outboxWriter.write("FOLLOW", userId, "FOLLOW_CHANGED",
                    followChangeMessage(userId, followUserId, false));
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
        return Result.ok(result.getData());
    }

    /**
     * 构造关注关系变更消息，供消费者维护 Redis 关注集合（原 afterCommit 逻辑迁移至此）。
     */
    private CacheSyncMessage followChangeMessage(Long userId, Long followUserId, boolean follow) {
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("followUserId", followUserId);
        data.put("follow", follow);
        CacheSyncMessage msg = new CacheSyncMessage("FOLLOW", userId, null, "FOLLOW_CHANGED");
        msg.setData(data);
        return msg;
    }
}
