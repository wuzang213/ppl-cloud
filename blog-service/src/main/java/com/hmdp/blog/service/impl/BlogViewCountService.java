package com.hmdp.blog.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.blog.domain.Blog;
import com.hmdp.blog.mapper.BlogMapper;
import com.hmdp.common.count.ConsumedOffsetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 博客浏览量累加的幂等入口：把「幂等登记」与「view_count 累加」放进同一个本地事务——累加失败一起回滚、幂等记录不残留。
 * 事务提交后调用方（{@code BlogViewConsumer}）才手动 ack offset，故「DB 已提交、offset 未提交」窗口内的重投会命中 consumed_offset
 * 主键冲突被跳过，累加只发生一次；单列成 bean 是为了让事务代理一定生效。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogViewCountService {

    private final ConsumedOffsetService consumedOffsetService;

    private final BlogMapper blogMapper;

    /** 幂等地把一条 Kafka 浏览量消息累加到 tb_blog.view_count。
     * @return {@code true} = 已累加；{@code false} = 重复消息已跳过（幂等唯一键为 topic + partition + offset） */
    @Transactional(rollbackFor = Exception.class)
    public boolean applyOnce(String topic, int partition, long offset, Long blogId, long count) {
        if (!consumedOffsetService.tryMarkConsumed(topic, partition, offset)) {
            // 这条消息之前已经处理过（DB 已提交但 offset 未提交导致的重投），直接跳过
            return false;
        }
        // 防御性收敛成非负整数：下面用字符串拼进 SQL，必须保证不可能带出注入字符
        long safeCount = Math.max(0L, count);
        blogMapper.update(null, new LambdaUpdateWrapper<Blog>()
                .eq(Blog::getId, blogId)
                .setSql("view_count = view_count + " + safeCount));
        return true;
    }
}
