package com.hmdp.common.exception;

/**
 * 业务非法异常
 */
public class BizIllegalException extends CommonException {
    public BizIllegalException(String message) {
        super(message, 500);
    }
}
