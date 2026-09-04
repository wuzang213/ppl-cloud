package com.hmdp.voucher.canal;

import com.hmdp.common.constants.CacheConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.utils.RabbitMqHelper;
import com.hmdp.voucher.domain.Voucher;
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
@CanalTable("tb_voucher")
public class VoucherCanalHandler implements EntryHandler<Voucher> {

    @Resource
    private RabbitMqHelper rabbitMqHelper;

    /**
     * 需要检查的业务字段（排除 create_time、update_time）
     */
    private static final List<String> BUSINESS_FIELDS = Arrays.asList(
            "shopId", "title", "subTitle", "rules", "payValue",
            "actualValue", "type", "status", "stock", "beginTime", "endTime"
    );

    @Override
    public void insert(Voucher voucher) {
        if (voucher == null || voucher.getId() == null) {
            log.warn("insert: voucher or id is null, skip");
            return;
        }
        log.debug("Canal监听到tb_voucher新增，id={}, shopId={}", voucher.getId(), voucher.getShopId());
        sendCacheSyncMessage(voucher.getId(), voucher.getShopId());
    }

    @Override
    public void update(Voucher before, Voucher after) {
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

            // 只有 update_time 变化 → 跳过缓存清理
            if (!hasBusinessChange) {
                log.debug("tb_voucher仅update_time变更，跳过缓存清理 id={}", after.getId());
                return;
            }

            log.debug("Canal监听到tb_voucher更新，id={}, shopId={}", after.getId(), after.getShopId());
            sendCacheSyncMessage(after.getId(), after.getShopId());

        } catch (Exception e) {
            log.error("VoucherCanalHandler.update 处理异常，id={}",
                    after != null ? after.getId() : "null", e);
        }
    }

    @Override
    public void delete(Voucher voucher) {
        if (voucher == null || voucher.getId() == null) {
            log.warn("delete: voucher or id is null, skip");
            return;
        }
        log.debug("Canal监听到tb_voucher删除，id={}, shopId={}", voucher.getId(), voucher.getShopId());
        sendCacheSyncMessage(voucher.getId(), voucher.getShopId());
    }

    private void sendCacheSyncMessage(Long id, Long shopId) {
        CacheSyncMessage msg = new CacheSyncMessage();
        msg.setType("VOUCHER");
        msg.setId(id);
        msg.setShopId(shopId);
        rabbitMqHelper.sendMessage(CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE, "", msg);
    }
}