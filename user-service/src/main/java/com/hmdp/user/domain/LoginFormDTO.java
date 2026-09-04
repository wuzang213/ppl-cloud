package com.hmdp.user.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "登录表单")
@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
}
