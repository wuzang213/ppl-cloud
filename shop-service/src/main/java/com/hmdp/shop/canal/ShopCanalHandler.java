package com.hmdp.shop.canal;

import com.hmdp.common.constants.CacheConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.utils.RabbitMqHelper;
import com.hmdp.shop.domain.Shop;
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
@CanalTable("tb_shop")
public class ShopCanalHandler implements EntryHandler<Shop> {

    @Resource
    private RabbitMqHelper rabbitMqHelper;

    /**
     * 需要检查的业务字段（排除 view_count、create_time、update_time）
     */
    private static final List<String> BUSINESS_FIELDS = Arrays.asList(
            "typeId", "name", "images", "area", "address",
            "x", "y", "avgPrice", "sold", "comments", "score", "openHours"
    );

    @Override
    public void insert(Shop shop) {
        if (shop == null || shop.getId() == null) {
            log.warn("insert: shop or id is null, skip");
            return;
        }
        log.debug("Canal监听到tb_shop新增，id={}", shop.getId());
        sendCacheSyncMessage(shop.getId());
    }

    @Override
    public void update(Shop before, Shop after) {
        try {
            if (before == null || after == null || after.getId() == null) {
                log.warn("update: before/after is null or after.id is null, skip");
                return;
            }

            BeanWrapper beforeWrapper = PropertyAccessorFactory.forBeanPropertyAccess(before);
            BeanWrapper afterWrapper = PropertyAccessorFactory.forBeanPropertyAccess(after);

            boolean hasBusinessChange = false;
            for (String field : BUSINESS_FIELDS) {
                Object beforeValue = beforeWrapper.getPropertyValue(field);
                Object afterValue = afterWrapper.getPropertyValue(field);
                if (!Objects.equals(beforeValue, afterValue)) {
                    hasBusinessChange = true;
                    log.debug("业务字段 {} 发生变化: {} -> {}", field, beforeValue, afterValue);
                    break;
                }
            }

            // 只有 view_count 变化 → 跳过缓存清理
            if (!hasBusinessChange) {
                log.debug("tb_shop仅view_count变更，跳过缓存清理 id={}", after.getId());
                return;
            }

            log.debug("Canal监听到tb_shop更新，id={}", after.getId());
            sendCacheSyncMessage(after.getId());

        } catch (Exception e) {
            log.error("ShopCanalHandler.update 处理异常，id={}",
                    after != null ? after.getId() : "null", e);
        }
    }

    @Override
    public void delete(Shop shop) {
        if (shop == null || shop.getId() == null) {
            log.warn("delete: shop or id is null, skip");
            return;
        }
        log.debug("Canal监听到tb_shop删除，id={}", shop.getId());
        sendCacheSyncMessage(shop.getId());
    }

    private void sendCacheSyncMessage(Long id) {
        CacheSyncMessage msg = new CacheSyncMessage();
        msg.setType("SHOP");
        msg.setId(id);
        rabbitMqHelper.sendMessage(CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE, "", msg);
    }
}