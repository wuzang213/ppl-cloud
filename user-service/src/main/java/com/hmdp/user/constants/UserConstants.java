package com.hmdp.user.constants;

public final class UserConstants {

    public static final String PHONE_INVALID = "手机号格式错误！";
    public static final String CODE_ERROR = "验证码错误";
    public static final String USER_NOT_FOUND = "用户不存在";
    public static final String PASSWORD_ERROR = "用户名或密码错误";
    public static final String PHONE_REGISTERED = "手机号已注册";
    public static final String PHONE_PASSWORD_INVALID = "手机号或密码格式错误";
    public static final String TOKEN_INVALID = "登录状态已失效，请重新登录";
    public static final String REFRESH_TOKEN_INVALID = "刷新令牌无效或已过期";
    /** 短信验证码发送过于频繁 */
    public static final String CODE_SEND_TOO_FREQUENT = "验证码发送过于频繁，请稍后再试";

    // 默认积分、等级、签到积分、订单积分、等级提升积分
    public static final int DEFAULT_CREDITS = 0;
    public static final int DEFAULT_LEVEL = 1;
    public static final int SIGN_CREDITS = 5;
    public static final int ORDER_CREDITS = 20;
    public static final int LEVEL_7_CREDITS = 200;
    public static final int LEVEL_8_CREDITS = 500;
    public static final int LEVEL_9_CREDITS = 1000;
    public static final int LEVEL_7 = 7;
    public static final int LEVEL_8 = 8;
    public static final int LEVEL_9 = 9;

    private UserConstants() {
    }
}
