package com.hmdp.voucher.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;

import com.hmdp.common.cache.CacheClient;
import com.hmdp.common.cache.SingleFlight;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.domain.Result;
import com.hmdp.common.outbox.OutboxWriter;
import com.hmdp.voucher.domain.SeckillVoucher;
import com.hmdp.voucher.domain.Voucher;
import com.hmdp.voucher.domain.VoucherDTO;
import com.hmdp.voucher.mapper.VoucherMapper;
import com.hmdp.voucher.service.ISeckillVoucherService;
import com.hmdp.voucher.service.IVoucherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.common.constants.RedisConstants.CACHE_VOUCHER_LIST_KEY;
import static com.hmdp.common.constants.RedisConstants.CACHE_VOUCHER_LIST_TTL;


@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private Cache<Long, List<Voucher>> voucherListCache;

    @Resource
    private OutboxWriter outboxWriter;

    @Resource
    private SingleFlight singleFlight;

    @Resource
    private CacheClient cacheClient;

    @Override
    @Transactional
    public Result addVoucher(VoucherDTO dto) {
        Voucher voucher = BeanUtil.copyProperties(dto, Voucher.class);
        save(voucher);
        outboxWriter.write("VOUCHER", voucher.getId(), "VOUCHER_CREATED",
                new CacheSyncMessage("VOUCHER", voucher.getId(), voucher.getShopId(), "VOUCHER_CREATED"));
        return Result.ok(voucher.getId());
    }

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 1. 查Caffeine本地缓存
        List<Voucher> vouchers = voucherListCache.getIfPresent(shopId);
        if (vouchers == null) {
            // 2. Redis + MySQL 整体交给 queryListWithPassThrough（自带随机 TTL + 空值防穿透），
            //    并用 single-flight 合并热点回源；不能只包 DB，否则并发请求依旧各自先打一次 Redis
            vouchers = singleFlight.run(CACHE_VOUCHER_LIST_KEY + shopId, () -> cacheClient.queryListWithPassThrough(
                    CACHE_VOUCHER_LIST_KEY, shopId, Voucher.class,
                    id -> getBaseMapper().queryVoucherOfShop(id),
                    CACHE_VOUCHER_LIST_TTL, TimeUnit.MINUTES));
            // 3. 回填Caffeine（返回空列表自然被缓存，无需额外判空返回）
            voucherListCache.put(shopId, vouchers);
        }
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(VoucherDTO dto) {
        Voucher voucher = BeanUtil.copyProperties(dto, Voucher.class);
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 秒杀库存预热改为写 outbox：Redis 库存 key 由 VoucherCacheListener 消费时写入，
        // 携带库存值，失败可对账重试，不再依赖请求线程内的 afterCommit
        Map<String, Object> data = new HashMap<>();
        data.put("stock", voucher.getStock());
        CacheSyncMessage msg = new CacheSyncMessage("VOUCHER", voucher.getId(), voucher.getShopId(),
                "SECKILL_VOUCHER_CREATED");
        msg.setData(data);
        outboxWriter.write("VOUCHER", voucher.getId(), "SECKILL_VOUCHER_CREATED", msg);
    }

    @Override
    @Transactional
    public Result updateVoucher(VoucherDTO dto) {
        Voucher voucher = BeanUtil.copyProperties(dto, Voucher.class);
        updateById(voucher);
        // 列表缓存失效统一由 outbox -> VoucherCacheListener 完成，无需再写 afterCommit
        outboxWriter.write("VOUCHER", voucher.getId(), "VOUCHER_UPDATED",
                new CacheSyncMessage("VOUCHER", voucher.getId(), voucher.getShopId(), "VOUCHER_UPDATED"));
        return Result.ok();
    }

    @Override
    @Transactional
    public Result deleteVoucher(Long id) {
        Voucher voucher = getById(id);
        removeById(id);
        // 列表缓存 + 秒杀库存 key 清理统一由 outbox -> VoucherCacheListener 完成
        Long shopId = voucher == null ? null : voucher.getShopId();
        outboxWriter.write("VOUCHER", id, "VOUCHER_DELETED",
                new CacheSyncMessage("VOUCHER", id, shopId, "VOUCHER_DELETED"));
        return Result.ok();
    }
}
