package com.hmdp.voucher.payment.support;

import com.hmdp.voucher.domain.VoucherOrder;
import com.hmdp.voucher.mapper.VoucherOrderMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内存态假 {@link VoucherOrderMapper}。
 *
 * <p>{@link #markPaidIfUnpaid} 严格按 {@code WHERE id = ? AND status = ?} 返回影响行数：
 * 「订单已被超时关单后收到成功回调」这类资损场景，就是靠这个返回值 0 来发现并拒绝对外应答成功。
 */
public class InMemoryVoucherOrderMapper extends BaseMapperStub<VoucherOrder> implements VoucherOrderMapper {

    private final Map<Long, VoucherOrder> byId = new LinkedHashMap<>();

    /** markPaidIfUnpaid 真正生效（推进为已支付）的次数 */
    public int markPaidCount;

    public void put(VoucherOrder order) {
        byId.put(order.getId(), order);
    }

    public VoucherOrder get(Long id) {
        return byId.get(id);
    }

    @Override
    public VoucherOrder selectById(java.io.Serializable id) {
        return byId.get(id);
    }

    @Override
    public int markPaidIfUnpaid(Long id, int paidStatus, int unpaidStatus, int payType, LocalDateTime payTime) {
        VoucherOrder order = byId.get(id);
        if (order == null || !Integer.valueOf(unpaidStatus).equals(order.getStatus())) {
            return 0;
        }
        order.setStatus(paidStatus);
        order.setPayType(payType);
        order.setPayTime(payTime);
        markPaidCount++;
        return 1;
    }
}
