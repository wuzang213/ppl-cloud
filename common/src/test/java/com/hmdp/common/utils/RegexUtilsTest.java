package com.hmdp.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RegexUtils} 的纯逻辑单测。
 *
 * <p>设计约束：<b>不加载 Spring 容器、不连任何外部依赖</b>。
 * 本项目的测试基线原本是 0，这一批只挑「改了会静默出错、且零依赖」的逻辑，
 * 目的是让门禁第一次真正变绿（而不是 {@code mvn test} 跑 0 个测试的假绿灯）。
 *
 * <p>注意语义：这三个方法的返回值是「<b>无效</b>」而非「有效」，
 * 所以合法输入要断言 {@code assertFalse}。
 */
class RegexUtilsTest {

    @Test
    @DisplayName("手机号：合法格式 isPhoneInvalid 返回 false")
    void phoneValid() {
        assertFalse(RegexUtils.isPhoneInvalid("13800138000"));
        assertFalse(RegexUtils.isPhoneInvalid("13912345678"));
        assertFalse(RegexUtils.isPhoneInvalid("19912345678"));
    }

    @Test
    @DisplayName("手机号：非法格式与空值返回 true")
    void phoneInvalid() {
        assertTrue(RegexUtils.isPhoneInvalid("12345678901"), "13x 之外的号段应判为非法");
        assertTrue(RegexUtils.isPhoneInvalid("1380013800"), "10 位应判为非法");
        assertTrue(RegexUtils.isPhoneInvalid("138001380000"), "12 位应判为非法");
        assertTrue(RegexUtils.isPhoneInvalid(null));
        assertTrue(RegexUtils.isPhoneInvalid(""));
        assertTrue(RegexUtils.isPhoneInvalid("   "));
    }

    @Test
    @DisplayName("邮箱：合法格式 isEmailInvalid 返回 false")
    void emailValid() {
        assertFalse(RegexUtils.isEmailInvalid("a@b.com"));
        assertFalse(RegexUtils.isEmailInvalid("user_1-x@mail.co.uk"));
    }

    @Test
    @DisplayName("邮箱：非法格式与空值返回 true")
    void emailInvalid() {
        assertTrue(RegexUtils.isEmailInvalid("a@b"), "缺少顶级域名应判为非法");
        assertTrue(RegexUtils.isEmailInvalid("@b.com"), "缺少本地部分应判为非法");
        assertTrue(RegexUtils.isEmailInvalid("a@.com"), "域名首段为空应判为非法");
        assertTrue(RegexUtils.isEmailInvalid(null));
        assertTrue(RegexUtils.isEmailInvalid(""));
    }

    @Test
    @DisplayName("验证码：必须恰好 6 位字母或数字")
    void code() {
        assertFalse(RegexUtils.isCodeInvalid("abc123"));
        assertFalse(RegexUtils.isCodeInvalid("123456"));
        assertFalse(RegexUtils.isCodeInvalid("ABCdef"));

        assertTrue(RegexUtils.isCodeInvalid("12345"), "5 位应判为非法");
        assertTrue(RegexUtils.isCodeInvalid("1234567"), "7 位应判为非法");
        assertTrue(RegexUtils.isCodeInvalid("abc-12"), "含非字母数字字符应判为非法");
        assertTrue(RegexUtils.isCodeInvalid(null));
        assertTrue(RegexUtils.isCodeInvalid(""));
    }
}
