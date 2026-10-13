package com.hmdp.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Result} 的纯逻辑单测。零外部依赖。
 *
 * <p>为什么值得测：{@code Result} 是 6 个服务共用的统一响应体，
 * 一旦 {@code ok()} / {@code fail()} 的字段口径被改（例如 success 取反、errorMsg 串到 data），
 * <b>全站所有接口一起坏</b>，而当前没有任何回归网。这几条断言就是那道网的最小版本。
 */
class ResultTest {

    @Test
    @DisplayName("ok()：success=true，其余字段为 null")
    void okEmpty() {
        Result<String> r = Result.ok();
        assertTrue(r.getSuccess());
        assertNull(r.getErrorMsg());
        assertNull(r.getData());
        assertNull(r.getTotal());
    }

    @Test
    @DisplayName("ok(data)：success=true，data 原样带出")
    void okWithData() {
        Result<String> r = Result.ok("payload");
        assertTrue(r.getSuccess());
        assertEquals("payload", r.getData());
        assertNull(r.getErrorMsg());
    }

    @Test
    @DisplayName("ok(list, total)：分页口径 —— data 与 total 同时带出")
    void okWithTotal() {
        Result<List<Integer>> r = Result.ok(Arrays.asList(1, 2), 2L);
        assertTrue(r.getSuccess());
        assertEquals(Arrays.asList(1, 2), r.getData());
        assertEquals(2L, r.getTotal());
    }

    @Test
    @DisplayName("fail(msg)：success=false，errorMsg 带出，data 为 null")
    void fail() {
        Result<String> r = Result.fail("boom");
        assertFalse(r.getSuccess());
        assertEquals("boom", r.getErrorMsg());
        assertNull(r.getData());
        assertNull(r.getTotal());
    }
}
