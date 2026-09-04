package com.hmdp.common.exception;

/**
 * 禁止访问异常
 */
public class ForbiddenException extends CommonException {
    public ForbiddenException(String message) {
        super(message, 403);
    }
}
