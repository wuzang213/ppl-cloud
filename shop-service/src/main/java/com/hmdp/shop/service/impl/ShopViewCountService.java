package com.hmdp.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.common.count.ConsumedOffsetService;
import com.hmdp.shop.domain.Shop;
import com.hmdp.shop.mapper.ShopMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 店铺浏览量累加的幂等入口，与 {@code BlogViewCountService} 结构对称。把「幂等登记」与「view_count 累加」放进同一个
 * 本地事务：两条语句同生共死，事务提交后由 {@code ShopViewConsumer} 手动 ack 提交 Kafka offset，故「DB 已提交、
 * offset 未提交」窗口内的重投会命中 consumed_offset 主键冲突被跳过。@see com.hmdp.common.count.ConsumedOffsetService */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopViewCountService {

    private final ConsumedOffsetService consumedOffsetService;

    private final ShopMapper shopMapper;

    /** 幂等地把一条 Kafka 浏览量消息累加到 tb_shop.view_count。
     * @return {@code true} = 已累加；{@code false} = 重复消息已跳过（幂等唯一键为 topic + partition + offset） */
    @Transactional(rollbackFor = Exception.class)
    public boolean applyOnce(String topic, int partition, long offset, Long shopId, long count) {
        if (!consumedOffsetService.tryMarkConsumed(topic, partition, offset)) {
            // 这条消息之前已经处理过（DB 已提交但 offset 未提交导致的重投），直接跳过
            return false;
        }
        // 防御性收敛成非负整数：下面用字符串拼进 SQL，必须保证不可能带出注入字符
        long safeCount = Math.max(0L, count);
        shopMapper.update(null, new LambdaUpdateWrapper<Shop>()
                .eq(Shop::getId, shopId)
                .setSql("view_count = view_count + " + safeCount));
        return true;
    }
}
