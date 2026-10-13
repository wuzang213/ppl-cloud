package com.hmdp.voucher.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.voucher.domain.VoucherOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    /** 支付成功后把订单推进为「已支付」（条件更新）。{@code WHERE status = 1}（UNPAID）是幂等与防资损关键：返回 0
     * 表示订单已不在未支付态（重复回调，或已被超时关单），调用方必须停止写 outbox，否则会给已关闭订单发积分。
     * SQL 用数字字面量（MyBatis 注解值须编译期常量，枚举进不去）；@param paidStatus=PAID.getValue()（2）@param unpaidStatus=UNPAID.getValue()（1）@param payType 2 支付宝 / 3 微信，来自 {@code PayProvider.getOrderPayType()} */
    @Update("UPDATE tb_voucher_order SET status = #{paidStatus}, pay_type = #{payType}, pay_time = #{payTime} "
            + "WHERE id = #{id} AND status = #{unpaidStatus}")
    int markPaidIfUnpaid(@Param("id") Long id,
                         @Param("paidStatus") int paidStatus,
                         @Param("unpaidStatus") int unpaidStatus,
                         @Param("payType") int payType,
                         @Param("payTime") LocalDateTime payTime);
}
