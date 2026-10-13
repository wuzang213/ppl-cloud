package com.hmdp.voucher.payment.support;

import com.hmdp.voucher.domain.Voucher;
import com.hmdp.voucher.mapper.VoucherMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存态假 {@link VoucherMapper}：只实现计价需要的 {@code selectById}。
 *
 * <p>刻意让 {@code queryVoucherOfShop} 抛异常 —— 支付链路完全不该碰它，
 * 一旦被调用就说明有人把订单查询混进了支付路径。
 */
public class VoucherMapperStub extends BaseMapperStub<Voucher> implements VoucherMapper {

    private final Map<Long, Voucher> byId = new LinkedHashMap<>();

    public void put(Voucher voucher) {
        byId.put(voucher.getId(), voucher);
    }

    @Override
    public Voucher selectById(java.io.Serializable id) {
        return byId.get(id);
    }

    @Override
    public List<Voucher> queryVoucherOfShop(Long shopId) {
        throw notImplemented("queryVoucherOfShop");
    }
}
