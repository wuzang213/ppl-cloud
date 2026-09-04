package com.hmdp.common.config;

import com.hmdp.common.domain.Result;
import com.hmdp.common.exception.CommonException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.NestedServletException;

import org.springframework.validation.BindException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(CommonException.class)
    public ResponseEntity<Result<Void>> handleCommonException(CommonException e) {
        log.error("业务异常 -> code: {}, message: {}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getCode()).body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .collect(Collectors.joining("|"));
        log.error("参数校验异常 -> {}", msg);
        return ResponseEntity.status(400).body(Result.fail(msg));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException e) {
        log.error("参数绑定异常", e);
        return ResponseEntity.status(400).body(Result.fail("请求参数格式错误"));
    }

    @ExceptionHandler(NestedServletException.class)
    public ResponseEntity<Result<Void>> handleNestedServletException(NestedServletException e) {
        log.error("请求参数处理异常", e);
        return ResponseEntity.status(400).body(Result.fail("请求参数处理异常"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("服务器内部异常", e);
        return ResponseEntity.status(500).body(Result.fail("服务器异常"));
    }
}