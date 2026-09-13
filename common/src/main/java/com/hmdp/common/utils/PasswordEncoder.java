package com.hmdp.common.utils;


import cn.hutool.core.util.RandomUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

public class PasswordEncoder {
    private final static BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /**
     * 加密密码
     * @param password
     * @return
     */
    public static String encode(String password) {
        if (password == null) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return ENCODER.encode(password);
    }

    public static Boolean matches(String encodedPassword, String rawPassword) {
        if (encodedPassword == null || rawPassword == null) {
            return false;
        }
        try {
            return ENCODER.matches(rawPassword, encodedPassword);
        } catch (IllegalArgumentException e) {
            // 格式不合法（比如脏数据），安静返回 false，不抛异常
            return false;
        }
    }
}
