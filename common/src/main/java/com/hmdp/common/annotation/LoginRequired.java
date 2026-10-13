package com.hmdp.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标注需要登录的 Controller 方法：配合 LoginRequiredAspect 在方法执行前校验 UserHolder.getUser()，
 * 为 null 则抛 UnauthorizedException(401)；不加此注解的接口默认公开访问。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginRequired {
}
