package com.hmdp.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注需要登录才能访问的 Controller 方法。
 * <p>
 * 拦截器 UserInfoInterceptor 只负责解析 header 存 UserHolder，不做鉴权。
 * 本注解配合 {@link com.hmdp.common.aspect.LoginRequiredAspect} AOP 切面，
 * 在方法执行前检查 UserHolder.getUser() 是否为 null，为 null 则抛 UnauthorizedException(401)。
 * <p>
 * 不加此注解的接口默认公开访问（注意 code review 防止遗漏）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginRequired {
}
