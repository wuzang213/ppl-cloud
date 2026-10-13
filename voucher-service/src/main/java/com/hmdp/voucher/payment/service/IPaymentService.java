package com.hmdp.voucher.payment.service;

import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.domain.NotifyOutcome;
import com.hmdp.voucher.payment.domain.PrepayResult;
import com.hmdp.voucher.payment.dto.PayNotifyCommand;

/** 支付单领域服务接口：预下单、支付结果落地、关单、查单。实现见
 * {@link com.hmdp.voucher.payment.service.impl.PaymentServiceImpl}。
 *
 * <p>与仓库 service 层「{@code IXxxService} 接口 + {@code XxxServiceImpl} 实现」的约定一致：接口只声明「做什么」，
 * 逻辑收敛在 Impl。三条原则（金额只服务端算 / 幂等靠 DB 条件更新 / 时间基准不可互串）的详细说明见实现类注释。 */
public interface IPaymentService {

    /** 发起支付（不带客户端 IP）。见实现类注释。@param orderId 秒杀券订单 id @param channelCode 渠道 code，留空用默认渠道 @param userId 当前登录用户 */
    PrepayResult prepay(Long orderId, String channelCode, Long userId);

    /** 发起支付（带客户端 IP）。见实现类注释。@param clientIp 用户端 IP，可空；微信 H5 必填 */
    PrepayResult prepay(Long orderId, String channelCode, Long userId, String clientIp);

    /** 落地一次「渠道支付结果」（支付宝回调 / 微信回调 / 主动查单三条入口共用）。见实现类注释。 */
    NotifyOutcome applyNotify(PayNotifyCommand cmd);

    /** 订单超时关单时，把该订单的 PENDING 支付单一起关掉。见实现类注释。@return 真正被关闭的行数（0 = 没有 PENDING 可关，正常） */
    int closePaymentForOrder(Long orderId);

    /** 查支付单（含归属校验）。订单存在但从未发起支付时返回 {@code null} —— 那是正常情况。 */
    Payment query(Long orderId, Long userId);
}
