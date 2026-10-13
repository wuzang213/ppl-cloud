package com.hmdp.search.service;

import com.hmdp.common.exception.BadRequestException;

/** 分页护栏：{@code size} 钳到上限、深分页直接 400。从 {@link com.hmdp.search.service.impl.SearchQueryServiceImpl} 原样提取，行为一字不变（异常仍是 {@link BadRequestException}），目的是让判定逻辑能在零外部依赖下被单测覆盖。
 * ⚠️ P0 原因：搜索免登录、任何人可打，ES 的 {@code max_result_window} 默认 10000，放任深翻页会让每个分片都堆 {@code from+size} 条命中参与排序、打满堆内存；放任 {@code size=10000} 等于一次请求拉全库。 */
public final class PagingGuard {

    /** 未指定 {@code size} 时的默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 非法页码（{@code null} / {@code <1}）回退到的页码 */
    public static final int DEFAULT_PAGE = 1;

    private PagingGuard() {
    }

    /** 规范化页码与每页条数，并施加两道护栏。{@code page} 为 {@code null}/{@code <1} 取 {@value #DEFAULT_PAGE}；{@code size} 为 {@code null}/{@code <1} 取 {@value #DEFAULT_PAGE_SIZE}，超过 {@code hmdp.search.max-page-size} 时钳制而非报错。
     * @return {@code [from, size]}，长度恒为 2；@throws BadRequestException 当 {@code from + size > hmdp.search.max-window}（深翻页）。
     */
    public static int[] clamp(Integer page, Integer size, int maxPageSize, int maxWindow) {
        int current = (page == null || page < 1) ? DEFAULT_PAGE : page;
        int pageSize = (size == null || size < 1) ? DEFAULT_PAGE_SIZE : Math.min(size, maxPageSize);
        int from = (current - 1) * pageSize;
        if (from + pageSize > maxWindow) {
            throw new BadRequestException("翻页过深，请缩小范围（from+size 上限 "
                    + maxWindow + "），或用 search_after 游标方式");
        }
        return new int[]{from, pageSize};
    }
}
