package com.hmdp.voucher.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;

import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.domain.Result;
import com.hmdp.common.outbox.OutboxWriter;
import com.hmdp.voucher.domain.SeckillVoucher;
import com.hmdp.voucher.domain.Voucher;
import com.hmdp.voucher.domain.VoucherDTO;
import com.hmdp.voucher.mapper.VoucherMapper;
import com.hmdp.voucher.service.ISeckillVoucherService;
import com.hmdp.voucher.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.common.constants.RedisConstants.CACHE_VOUCHER_LIST_KEY;
import static com.hmdp.common.constants.RedisConstants.CACHE_VOUCHER_LIST_TTL;


@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Cache<Long, List<Voucher>> voucherListCache;

    @Resource
    private OutboxWriter outboxWriter;

    @Override
    @Transactional
    public Result addVoucher(VoucherDTO dto) {
        Voucher voucher = BeanUtil.copyProperties(dto, Voucher.class);
        save(voucher);
        outboxWriter.write("VOUCHER", voucher.getId(), "VOUCHER_CREATED",
                new CacheSyncMessage("VOUCHER", voucher.getId(), voucher.getShopId()));
        return Result.ok(voucher.getId());
    }

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 1. 查Caffeine本地缓存
        List<Voucher> vouchers = voucherListCache.getIfPresent(shopId);
        if (vouchers != null) {
            return Result.ok(vouchers);
        }
        // 2. 查Redis
        String key = CACHE_VOUCHER_LIST_KEY + shopId;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            vouchers = JSONUtil.toList(json, Voucher.class);
            voucherListCache.put(shopId, vouchers);
            return Result.ok(vouchers);
        }
        // 3. 查MySQL
        vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        if (vouchers == null || vouchers.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 4. 回填Redis + Caffeine
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(vouchers), CACHE_VOUCHER_LIST_TTL, TimeUnit.MINUTES);
        voucherListCache.put(shopId, vouchers);
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
        outboxWriter.write("VOUCHER", voucher.getId(), "SECKILL_VOUCHER_CREATED",
                new CacheSyncMessage("VOUCHER", voucher.getId(), voucher.getShopId()));
        Long voucherId = voucher.getId();
        Integer stock = voucher.getStock();
        afterCommit(() -> stringRedisTemplate.opsForValue().set(
                "seckill:{" + voucherId + "}:stock",
                stock.toString()));
    }

    private void afterCommit(Runnable runnable) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runnable.run();
            }
        });
    }
}
