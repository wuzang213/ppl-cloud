package com.hmdp.shop.canal;

import com.hmdp.common.constants.CacheConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.utils.RabbitMqHelper;
import com.hmdp.shop.domain.ShopType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.javatool.canal.client.annotation.CanalTable;
import top.javatool.canal.client.handler.EntryHandler;

import javax.annotation.Resource;

@Slf4j
@Component
@CanalTable("tb_shop_type")
public class ShopTypeCanalHandler implements EntryHandler<ShopType> {

    @Resource
    private RabbitMqHelper rabbitMqHelper;

    @Override
    public void insert(ShopType shopType) {
        log.debug("Canal监听到tb_shop_type新增，id={}", shopType.getId());
        CacheSyncMessage msg = new CacheSyncMessage();
        msg.setType("SHOP_TYPE");
        rabbitMqHelper.sendMessage(CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE, "", msg);
    }

    @Override
    public void update(ShopType before, ShopType after) {
        log.debug("Canal监听到tb_shop_type更新，id={}", after.getId());
        CacheSyncMessage msg = new CacheSyncMessage();
        msg.setType("SHOP_TYPE");
        rabbitMqHelper.sendMessage(CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE, "", msg);
    }

    @Override
    public void delete(ShopType shopType) {
        log.debug("Canal监听到tb_shop_type删除，id={}", shopType.getId());
        CacheSyncMessage msg = new CacheSyncMessage();
        msg.setType("SHOP_TYPE");
        rabbitMqHelper.sendMessage(CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE, "", msg);
    }
}