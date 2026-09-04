package com.hmdp.blog.canal;

import com.hmdp.common.constants.CacheConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.utils.RabbitMqHelper;
import com.hmdp.blog.domain.Blog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.stereotype.Component;
import top.javatool.canal.client.annotation.CanalTable;
import top.javatool.canal.client.handler.EntryHandler;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Slf4j
@Component
@CanalTable("tb_blog")
public class BlogCanalHandler implements EntryHandler<Blog> {

    @Resource
    private RabbitMqHelper rabbitMqHelper;

    /**
     * 需要检查的业务字段（排除 view_count、create_time、update_time）
     */
    private static final List<String> BUSINESS_FIELDS = Arrays.asList(
            "shopId", "userId", "title", "images", "content", "liked", "comments"
    );

    @Override
    public void insert(Blog blog) {
        if (blog == null || blog.getId() == null) {
            log.warn("insert: blog or id is null, skip");
            return;
        }
        log.debug("Canal监听到tb_blog新增，id={}", blog.getId());
        sendCacheSyncMessage(blog.getId());
    }

    @Override
    public void update(Blog before, Blog after) {
        try {
            if (before == null || after == null || after.getId() == null) {
                log.warn("before/after is null or after.id is null, skip update processing");
                return;
            }

            BeanWrapper beforeWrapper = PropertyAccessorFactory.forBeanPropertyAccess(before);
            BeanWrapper afterWrapper = PropertyAccessorFactory.forBeanPropertyAccess(after);

            boolean hasBusinessChange = false;
            for (String field : BUSINESS_FIELDS) {
                Object beforeValue = beforeWrapper.getPropertyValue(field);
                Object afterValue = afterWrapper.getPropertyValue(field);

                // 只有 before 和 after 都有值且不同时，才算业务字段变化
                // Canal 的 before 对象只包含变更字段，未变更字段为 null
                if (beforeValue != null && afterValue != null && !Objects.equals(beforeValue, afterValue)) {
                    hasBusinessChange = true;
                    log.debug("业务字段 {} 发生变化: {} -> {}", field, beforeValue, afterValue);
                    break;
                }
            }

            // 只有 view_count 变化  跳过缓存清理
            if (!hasBusinessChange) {
                log.debug("tb_blog仅view_count变更，跳过缓存清理 id={}", after.getId());
                return;
            }

            log.debug("Canal监听到tb_blog更新，id={}", after.getId());
            sendCacheSyncMessage(after.getId());

        } catch (Exception e) {
            log.error("BlogCanalHandler.update 处理异常，id={}",
                    after != null ? after.getId() : "null", e);
            // 不抛出异常，让 Canal 框架能正常 ACK
        }
    }

    @Override
    public void delete(Blog blog) {
        if (blog == null || blog.getId() == null) {
            log.warn("delete: blog or id is null, skip");
            return;
        }
        log.debug("Canal监听到tb_blog删除，id={}", blog.getId());
        sendCacheSyncMessage(blog.getId());
    }

    private void sendCacheSyncMessage(Long id) {
        CacheSyncMessage msg = new CacheSyncMessage();
        msg.setType("BLOG");
        msg.setId(id);
        rabbitMqHelper.sendMessage(CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE, "", msg);
    }
}