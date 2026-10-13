package com.hmdp.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.common.constants.SystemConstants;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.exception.BadRequestException;
import com.hmdp.common.utils.UserHolder;
import com.hmdp.shop.domain.Booking;
import com.hmdp.shop.domain.BookingDTO;
import com.hmdp.shop.mapper.BookingMapper;
import com.hmdp.shop.service.IBookingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 预约服务。
 *
 * <p>⚠️ 三个方法都**不加 `@Transactional`**：`create` 是单条 INSERT（幂等由唯一键兜底），
 * `cancel` 的"读→改"两侧都带 `user_id` 条件、重复执行结果相同 ⇒ 不需要事务，
 * 也避免"在事务里捕获唯一键冲突"这种需要额外推敲的角落。
 */
@Slf4j
@Service
public class BookingServiceImpl extends ServiceImpl<BookingMapper, Booking> implements IBookingService {

    /** 单次预约人数上限（与 agent 侧 `plan_slots` 的槽位值域保持一致：1~20）。 */
    private static final int MAX_PEOPLE = 20;
    private static final int DEFAULT_PEOPLE = 2;

    @Override
    public Result create(BookingDTO dto) {
        validate(dto);
        UserDTO user = UserHolder.getUser();

        LocalDateTime now = LocalDateTime.now();
        Booking booking = new Booking()
                .setUserId(user.getId())                     // 🔴 只认登录态，不认客户端传的 userId
                .setShopId(dto.getShopId())
                .setBookingTime(dto.getBookingTime())
                .setPeople(dto.getPeople() == null ? DEFAULT_PEOPLE : dto.getPeople())
                .setStatus(Booking.STATUS_PENDING)
                .setRemark(dto.getRemark())
                .setCreateTime(now)
                .setUpdateTime(now);

        try {
            save(booking);
        } catch (DuplicateKeyException e) {
            // 幂等：同 (user, shop, booking_time) 已存在 ⇒ 回查既有单返回，不新建。
            // 🔴 必须靠唯一键兜底，**不能"先查再插"** —— 并发下两次查询都会判"不存在"、双双插入。
            Booking existing = findExisting(user.getId(), dto.getShopId(), dto.getBookingTime());
            if (existing == null) {
                // 不是 uk_user_shop_time 撞的（别的约束冲突）⇒ 原样抛出，
                // 别把自己的 bug 伪装成"你已预约过"。
                throw e;
            }
            log.debug("预约幂等命中，返回既有单 orderId={}", existing.getId());
            // 🔴 `created=false` 让调用方能区分"新建成功"与"命中既有单"（`plans/0019` 真机验收发现）：
            //    两者原本都只返回 orderId，agent 侧会把幂等命中也播报成"已为你预约"，属**不准确陈述**。
            return Result.ok(orderResult(existing.getId(), false));
        }
        return Result.ok(orderResult(booking.getId(), true));
    }

    /**
     * 预约提交结果载荷。
     *
     * @param orderId  单号
     * @param created  {@code true}=本次新建；{@code false}=幂等命中既有单（**没有新建**）
     */
    private Map<String, Object> orderResult(Long orderId, boolean created) {
        Map<String, Object> data = new HashMap<>(2);
        data.put("orderId", orderId);
        data.put("created", created);
        return data;
    }

    @Override
    public Result myBookings(Integer current) {
        UserDTO user = UserHolder.getUser();
        Page<Booking> page = query()
                .eq("user_id", user.getId())
                .orderByDesc("booking_time")
                .page(new Page<>(current == null ? 1 : current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @Override
    public Result cancel(Long orderId) {
        if (orderId == null) {
            throw new BadRequestException("缺少 orderId");
        }
        UserDTO user = UserHolder.getUser();
        Booking booking = getById(orderId);
        if (booking == null || !Objects.equals(booking.getUserId(), user.getId())) {
            // ⚠️ 刻意**不区分**"单子不存在"与"不是你的单"：区分了就等于告诉任何人
            // "这个单号存在"（可被用来枚举他人订单）。
            throw new BadRequestException("预约单不存在");
        }
        if (Objects.equals(booking.getStatus(), Booking.STATUS_CANCELLED)) {
            return Result.ok(Boolean.TRUE);              // 幂等：已取消即成功
        }
        boolean updated = update(new LambdaUpdateWrapper<Booking>()
                .eq(Booking::getId, orderId)
                .eq(Booking::getUserId, user.getId())    // 双保险：即使上面判过，写路径也再限定本人
                .set(Booking::getStatus, Booking.STATUS_CANCELLED)
                .set(Booking::getUpdateTime, LocalDateTime.now()));
        return Result.ok(updated);
    }

    private Booking findExisting(Long userId, Long shopId, LocalDateTime bookingTime) {
        List<Booking> found = query()
                .eq("user_id", userId)
                .eq("shop_id", shopId)
                .eq("booking_time", bookingTime)
                .list();
        return found.isEmpty() ? null : found.get(0);
    }

    /** 入参校验放 Service 层（全仓没有 `@Valid` 用法），非法一律 `BadRequestException`(400)。 */
    private void validate(BookingDTO dto) {
        if (dto == null || dto.getShopId() == null) {
            throw new BadRequestException("缺少 shopId");
        }
        if (dto.getBookingTime() == null) {
            throw new BadRequestException("缺少 bookingTime");
        }
        if (dto.getBookingTime().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("预约时间不能早于当前时间");
        }
        Integer people = dto.getPeople();
        if (people != null && (people < 1 || people > MAX_PEOPLE)) {
            throw new BadRequestException("人数需在 1~" + MAX_PEOPLE + " 之间");
        }
        if (dto.getRemark() != null && dto.getRemark().length() > 255) {
            throw new BadRequestException("备注过长（最多 255 字）");
        }
    }
}
