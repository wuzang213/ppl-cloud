package com.hmdp.shop.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 门店预约单（表 `tb_booking`，DDL 见 `sql/booking.sql`）。
 *
 * <p>⚠️ 三个时间列口径相同、但**都不同于**"用 DB 默认值"那一类（改前必读 `sql/README.md` §7.2）：
 * 本实例 {@code NOW()} 是 UTC，而这三列都会**原样返回前端**（我在预约列表里直接返回实体），
 * 所以一律由**应用**写本地墙钟 —— 与 {@link ShopFavorite#getCreateTime()}、以及 `hmdp_agent` 两表同口径。
 * {@code bookingTime} 来自请求参数，另外两列由 Service 写入。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_booking")
public class Booking implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待确认 */
    public static final int STATUS_PENDING = 0;
    /** 已确认 */
    public static final int STATUS_CONFIRMED = 1;
    /** 已取消 */
    public static final int STATUS_CANCELLED = 2;
    /** 已完成 */
    public static final int STATUS_FINISHED = 3;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 下单用户（来自登录态，不接受客户端传入） */
    private Long userId;

    private Long shopId;

    /** 预约到店时间（用户选的，前端展示用它） */
    private LocalDateTime bookingTime;

    private Integer people;

    /** 见本类 {@code STATUS_*} 常量 */
    private Integer status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
