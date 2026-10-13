package com.hmdp.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.common.domain.Result;
import com.hmdp.shop.domain.Booking;
import com.hmdp.shop.domain.BookingDTO;

public interface IBookingService extends IService<Booking> {

    /** 提交预约（**幂等**：同用户 + 同门店 + 同到店时间 ⇒ 返回既有单号）。 */
    Result create(BookingDTO dto);

    /** 我的预约分页列表。 */
    Result myBookings(Integer current);

    /** 取消预约（只能取消本人的，且重复取消视为成功）。 */
    Result cancel(Long orderId);
}
