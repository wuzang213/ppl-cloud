package com.hmdp.user.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 修改密码表单。
 */
@Schema(description = "修改密码表单")
@Data
public class ChangePasswordDTO {

    @Schema(description = "原密码")
    private String oldPassword;

    @Schema(description = "新密码（6-20 位）")
    private String newPassword;
}
