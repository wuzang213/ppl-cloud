package com.hmdp.common.exception;

/**
 * 未授权异常
 */
public class UnauthorizedException extends CommonException {
    public UnauthorizedException(String message) {
        super(message, 401);
    }
}
