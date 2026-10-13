package com.hmdp.common.exception;

/** 请求参数错误异常，HTTP 400。 */
public class BadRequestException extends CommonException {
    public BadRequestException(String message) {
        super(message, 400);
    }
}
