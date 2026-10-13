package com.hmdp.common.utils;

import cn.hutool.core.util.StrUtil;
import com.hmdp.common.constants.RegexPatterns;

/**
 * @author 虎哥
 */
public class RegexUtils {
    /** 校验手机号格式，无效返回 true。 */
    public static boolean isPhoneInvalid(String phone){
        return mismatch(phone, RegexPatterns.PHONE_REGEX);
    }
    /** 校验邮箱格式，无效返回 true。 */
    public static boolean isEmailInvalid(String email){
        return mismatch(email, RegexPatterns.EMAIL_REGEX);
    }

    /** 校验验证码格式，无效返回 true。 */
    public static boolean isCodeInvalid(String code){
        return mismatch(code, RegexPatterns.VERIFY_CODE_REGEX);
    }

    // 校验是否不符合正则格式
    private static boolean mismatch(String str, String regex){
        if (StrUtil.isBlank(str)) {
            return true;
        }
        return !str.matches(regex);
    }
}
