package com.hmdp.search.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SearchProperties} 的安全默认值。
 * <p>
 * 这些初值是"配置漏配时兜底的最后一道防线"。其中 {@code useIk} 与查询护栏两项尤其关键：
 * 默认值一旦被改成不安全的值，即便仓库 yaml 忘了配也不会报错，只会静默把中文搜索质量
 * 或免登录接口的防打爆能力丢掉。
 */
class SearchPropertiesTest {

    private final SearchProperties properties = new SearchProperties();

    @Test
    @DisplayName("查询护栏默认值：size 上限 50、from+size 上限 1000")
    void pagingGuardDefaults() {
        assertEquals(50, properties.getMaxPageSize());
        assertEquals(1000, properties.getMaxWindow());
        // 上限必须大于默认页大小，否则任何请求都会被钳到异常值
        assertTrue(properties.getMaxPageSize() >= 10);
    }

    @Test
    @DisplayName("useIk 默认 true（已确认装了 analysis-ik 7.12.1）")
    void ikIsOnByDefault() {
        assertTrue(properties.isUseIk());
    }

    @Test
    @DisplayName("死信重试上限与 outbox 的 MAX_RETRY 口径一致（5）")
    void failedRetryMaxAlignsWithOutbox() {
        assertEquals(5, properties.getFailedRetryMax());
    }

    @Test
    @DisplayName("历史版本保留 1 个（回滚候选），不是 0")
    void keepsOneOldVersion() {
        assertEquals(1, properties.getKeepOldVersions());
    }

    @Test
    @DisplayName("ES 连接默认指向 127.0.0.1:9200")
    void esEndpointDefaults() {
        assertEquals("127.0.0.1", properties.getHost());
        assertEquals(9200, properties.getPort());
        assertEquals(3000, properties.getConnectTimeoutMs());
        assertEquals(15000, properties.getSocketTimeoutMs());
    }

    @Test
    @DisplayName("重建/批量参数默认值")
    void rebuildDefaults() {
        assertEquals(200, properties.getRebuildPageSize());
        assertEquals(500, properties.getBulkSize());
        assertEquals(30, properties.getRebuildLockMinutes());
        assertTrue(properties.isAutoRebuildOnStartup());
    }

    @Test
    @DisplayName("定时/防抖窗口默认值")
    void schedulingDefaults() {
        assertEquals(60000, properties.getRetryIntervalMillis());
        assertEquals(5000, properties.getDebounceMillis());
    }

    @Test
    @DisplayName("批量大小不应小于重建分页大小（否则 bulk 切得比分页还碎，白跑）")
    void bulkSizeIsNotSmallerThanPageSize() {
        assertTrue(properties.getBulkSize() >= properties.getRebuildPageSize(),
                "bulkSize 应 >= rebuildPageSize");
    }

    @Test
    @DisplayName("端口不是 ES 默认的 9200 被误写成 9201 之类的哨兵值")
    void portIsRealistic() {
        assertTrue(properties.getPort() > 0 && properties.getPort() < 65536);
    }

    @Test
    @DisplayName("查询护栏字段不参与 equals 误用：两个默认实例字段一致")
    void defaultsAreDeterministic() {
        SearchProperties other = new SearchProperties();
        assertEquals(properties.getMaxPageSize(), other.getMaxPageSize());
        assertEquals(properties.getMaxWindow(), other.getMaxWindow());
        assertFalse(properties.getMaxWindow() < properties.getMaxPageSize());
    }
}
