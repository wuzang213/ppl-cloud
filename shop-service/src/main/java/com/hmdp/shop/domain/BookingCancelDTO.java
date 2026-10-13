package com.hmdp.shop.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 取消预约的表单。 */
@Schema(description = "取消预约表单")
@Data
public class BookingCancelDTO {

    private Long orderId;
}
