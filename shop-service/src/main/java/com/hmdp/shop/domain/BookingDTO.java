package com.hmdp.shop.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提交预约的表单。
 *
 * <p>⚠️ **没有 `userId`**：下单用户一律取自登录态（`UserHolder`），
 * 客户端传什么都不认 —— 否则任何人都能替别人下单。
 */
@Schema(description = "预约提交表单")
@Data
public class BookingDTO {

    private Long shopId;

    /** 到店时间；格式与 {@code VoucherDTO} 的券时间口径一致 */
    @Schema(description = "预约到店时间", example = "2026-10-05 18:30:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime bookingTime;

    private Integer people;

    private String remark;
}
