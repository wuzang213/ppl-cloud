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
        send(shopType.getId());
    }

    @Override
    public void update(ShopType before, ShopType after) {
        log.debug("Canal监听到tb_shop_type更新，id={}", after.getId());
        send(after.getId());
    }

    @Override
    public void delete(ShopType shopType) {
        log.debug("Canal监听到tb_shop_type删除，id={}", shopType.getId());
        send(shopType.getId());
    }

    /** 广播分类变更到 CACHE_SYNC_FANOUT_EXCHANGE（type=SHOP_TYPE）。必须带上 id：search-service 收到后要对
     * {@code hmdp_shop} 索引做 {@code _update_by_query(term: typeId)} 刷新分类名，不知道是哪个分类就只能全量
     * 刷所有店铺文档（早期 id 为 null 时「分类改名」只能退化成全量刷新）。 */
    private void send(Long typeId) {
        CacheSyncMessage msg = new CacheSyncMessage();
        msg.setType("SHOP_TYPE");
        msg.setId(typeId);
        rabbitMqHelper.sendMessage(CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE, "", msg);
    }
}