package com.hmdp.common.aspect;

import com.hmdp.common.annotation.LoginRequired;
import com.hmdp.common.exception.UnauthorizedException;
import com.hmdp.common.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * @LoginRequired 注解的 AOP 切面。
 * <p>
 * 在标注了 @LoginRequired 的 Controller 方法执行前，
 * 检查 UserHolder.getUser() 是否为 null。
 * 为 null 说明请求未携带有效 user-info header（未登录），抛 UnauthorizedException(401)，
 * 由 GlobalExceptionHandler 统一处理。
 */
@Slf4j
@Aspect
@Component
public class LoginRequiredAspect {

    @Before("@annotation(loginRequired)")
    public void checkLogin(LoginRequired loginRequired) {
        if (UserHolder.getUser() == null) {
            throw new UnauthorizedException("未登录");
        }
    }
}
