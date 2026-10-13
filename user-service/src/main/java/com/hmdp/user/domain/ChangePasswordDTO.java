package com.hmdp.user.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 修改密码表单。必须携带原密码：否则仅凭 accessToken 就能改密，token 泄露会升级为「永久接管账号」（攻击者改掉密码后原主再也进不来），校验原密码把危害限制在 token 有效期内。
 */
@Schema(description = "修改密码表单")
@Data
public class ChangePasswordDTO {

    @Schema(description = "原密码")
    private String oldPassword;

    @Schema(description = "新密码（6-20 位）")
    private String newPassword;
}
