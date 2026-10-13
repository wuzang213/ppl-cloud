package com.hmdp.search.service;

import com.hmdp.common.exception.BadRequestException;
import com.hmdp.search.config.SearchProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页护栏（免登录接口的 P0）。
 * <p>
 * 三道判定：{@code size} 钳制 / {@code from+size} 超限直接 400 / 非法值回退默认。
 * 这些逻辑原先在 {@code SearchQueryService} 私有方法里，无法在零外部依赖下覆盖。
 *
 * <h3>已知边界（不断言，属既有实现）</h3>
 * {@code page} 取极大值（如 {@code Integer.MAX_VALUE}）时 {@code (page-1)*pageSize} 会**整型溢出**成负数，
 * 于是护栏判不出"太深"，会把负的 {@code from} 交给 ES（ES 侧报错）。本期只做"提取纯函数"，
 * 不改变行为，故不断言该分支 —— 记录在此，避免被误认为"测试漏了"。
 */
class PagingGuardTest {

    /** 与 hmdp.search 的默认配置一致（SearchProperties 的字段初值） */
    private static final int MAX_PAGE_SIZE = new SearchProperties().getMaxPageSize();

    private static final int MAX_WINDOW = new SearchProperties().getMaxWindow();

    @Test
    @DisplayName("size=10000 被钳到上限 50，而不是报错")
    void sizeIsClampedNotRejected() {
        int[] paging = PagingGuard.clamp(1, 10000, MAX_PAGE_SIZE, MAX_WINDOW);
        assertArrayEquals(new int[]{0, 50}, paging);
    }

    @Test
    @DisplayName("size 取 Integer.MAX_VALUE 也只钳到上限（不会整型溢出）")
    void hugeSizeIsClamped() {
        int[] paging = PagingGuard.clamp(1, Integer.MAX_VALUE, MAX_PAGE_SIZE, MAX_WINDOW);
        assertArrayEquals(new int[]{0, MAX_PAGE_SIZE}, paging);
    }

    @Test
    @DisplayName("page / size 为 null 时回退到 1 / 10")
    void nullFallsBackToDefaults() {
        assertArrayEquals(new int[]{0, 10}, PagingGuard.clamp(null, null, MAX_PAGE_SIZE, MAX_WINDOW));
        assertArrayEquals(new int[]{0, 20}, PagingGuard.clamp(null, 20, MAX_PAGE_SIZE, MAX_WINDOW));
    }

    @Test
    @DisplayName("page < 1 视为第 1 页；size < 1 视为 10")
    void nonPositiveFallsBackToDefaults() {
        assertArrayEquals(new int[]{0, 10}, PagingGuard.clamp(0, 0, MAX_PAGE_SIZE, MAX_WINDOW));
        assertArrayEquals(new int[]{0, 10}, PagingGuard.clamp(-5, -1, MAX_PAGE_SIZE, MAX_WINDOW));
    }

    @Test
    @DisplayName("from 按 (page-1)*size 计算")
    void fromIsOffset() {
        assertArrayEquals(new int[]{0, 10}, PagingGuard.clamp(1, 10, MAX_PAGE_SIZE, MAX_WINDOW));
        assertArrayEquals(new int[]{10, 10}, PagingGuard.clamp(2, 10, MAX_PAGE_SIZE, MAX_WINDOW));
        assertArrayEquals(new int[]{90, 10}, PagingGuard.clamp(10, 10, MAX_PAGE_SIZE, MAX_WINDOW));
    }

    @Test
    @DisplayName("from+size 恰好等于上限：放行（边界是 > 而不是 >=）")
    void exactlyAtWindowIsAllowed() {
        // maxWindow=1000：page=20,size=50 -> 950+50=1000 -> 放行
        assertArrayEquals(new int[]{950, 50}, PagingGuard.clamp(20, 50, MAX_PAGE_SIZE, MAX_WINDOW));
        // page=100,size=10 -> 990+10=1000 -> 放行
        assertArrayEquals(new int[]{990, 10}, PagingGuard.clamp(100, 10, MAX_PAGE_SIZE, MAX_WINDOW));
    }

    @Test
    @DisplayName("from+size 超过上限 1 就抛 BadRequestException（深翻页）")
    void beyondWindowByOneThrows() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> PagingGuard.clamp(21, 50, MAX_PAGE_SIZE, MAX_WINDOW));
        assertTrue(ex.getMessage().contains("翻页过深"), "消息应说明翻页过深，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(String.valueOf(MAX_WINDOW)),
                "消息里应带上限数值 " + MAX_WINDOW + "，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("search_after"),
                "消息里应给出替代方案 search_after，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("深翻页抛的是 BadRequestException（映射 HTTP 400，不是 500）")
    void deepPagingUsesBadRequest() {
        // 断言异常**类型**而不只是"抛了异常"：若被改成 IllegalStateException 就是 500，
        // 免登录接口对用户参数错误回 5xx 会污染告警。
        assertThrows(BadRequestException.class,
                () -> PagingGuard.clamp(101, 10, MAX_PAGE_SIZE, MAX_WINDOW));
    }

    @Test
    @DisplayName("先钳 size 再判 from+size：size=10000 不会被误判成深翻页")
    void clampHappensBeforeWindowCheck() {
        // 若顺序写反（先用原始 size 判窗口），page=1&size=10000 会被当成 from+size=10000 而误报 400
        int[] paging = PagingGuard.clamp(1, 10000, MAX_PAGE_SIZE, MAX_WINDOW);
        assertEquals(50, paging[1]);
        assertEquals(0, paging[0]);
    }

    @Test
    @DisplayName("返回值恒为长度 2 的 int[]（调用方按 [0]=from、[1]=size 取值）")
    void returnShapeIsStable() {
        assertEquals(2, PagingGuard.clamp(3, 15, MAX_PAGE_SIZE, MAX_WINDOW).length);
    }
}
