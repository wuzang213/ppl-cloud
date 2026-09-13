package com.hmdp.blog.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.blog.domain.Blog;
import com.hmdp.blog.mapper.BlogMapper;
import com.hmdp.common.count.ConsumedOffsetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 博客浏览量累加的幂等入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogViewCountService {

    private final ConsumedOffsetService consumedOffsetService;

    private final BlogMapper blogMapper;

    /**
     * 幂等地把一条 Kafka 浏览量消息累加到 tb_blog.view_count。
     *
     * @param topic     Kafka topic，与 partition/offset 一起构成幂等唯一键
     * @param partition Kafka 分区号
     * @param offset    Kafka 位点
     * @param blogId    博客 id
     * @param count     本次累加量
     * @return {@code true} = 已累加；{@code false} = 重复消息，已跳过
     */
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
