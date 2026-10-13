package com.hmdp.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CollUtils} 的纯逻辑单测。零外部依赖。
 *
 * <p>重点覆盖两处「容易被顺手改坏、且不会报错」的语义：
 * <ul>
 *   <li>{@code convertToXxx} 对空集合/ null 返回 <b>null</b>（而不是空列表）——调用方依赖这个约定；</li>
 *   <li>{@code join} / {@code joinIgnoreNull} 对空集合返回 <b>null</b>（而不是空字符串）。</li>
 * </ul>
 */
class CollUtilsTest {

    @Test
    @DisplayName("空集合工厂：返回不可变空集合")
    void empties() {
        assertTrue(CollUtils.emptyList().isEmpty());
        assertTrue(CollUtils.emptySet().isEmpty());
        assertTrue(CollUtils.emptyMap().isEmpty());
    }

    @Test
    @DisplayName("单元素工厂")
    void singletons() {
        assertEquals(1, CollUtils.singletonList("a").size());
        assertEquals("a", CollUtils.singletonList("a").get(0));
        assertEquals(1, CollUtils.singletonSet("a").size());
    }

    @Test
    @DisplayName("convertToInteger：非空转换，空/null 返回 null")
    void convertToInteger() {
        assertEquals(Arrays.asList(1, 2, 3), CollUtils.convertToInteger(Arrays.asList("1", "2", "3")));
        assertNull(CollUtils.convertToInteger(null), "null 应返回 null");
        assertNull(CollUtils.convertToInteger(Collections.emptyList()), "空集合应返回 null");
    }

    @Test
    @DisplayName("convertToLong：非空转换，空/null 返回 null")
    void convertToLong() {
        List<Long> got = CollUtils.convertToLong(Arrays.asList("10", "20"));
        assertNotNull(got);
        assertEquals(Arrays.asList(10L, 20L), got);
        assertNull(CollUtils.convertToLong(null));
        assertNull(CollUtils.convertToLong(Collections.emptyList()));
    }

    @Test
    @DisplayName("join：空/null 返回 null；否则用分隔符拼接")
    void join() {
        assertEquals("a,b,c", CollUtils.join(Arrays.asList("a", "b", "c"), ","));
        // 必须显式转型：CollUtils 继承自 hutool CollectionUtil，
        // 裸 null 会在 join(Collection,CharSequence) 与继承来的 join(Iterator,CharSequence) 之间二义。
        assertNull(CollUtils.join((Collection<String>) null, ","));
        assertNull(CollUtils.join(Collections.<String>emptyList(), ","));
    }

    @Test
    @DisplayName("joinIgnoreNull：跳过 null 元素；全 null 时返回 null")
    void joinIgnoreNull() {
        assertEquals("a,b", CollUtils.joinIgnoreNull(Arrays.asList("a", null, "b"), ","));
        assertNull(CollUtils.joinIgnoreNull(Arrays.asList(null, null), ","));
        assertNull(CollUtils.joinIgnoreNull(null, ","));
        assertNull(CollUtils.joinIgnoreNull(Collections.<String>emptyList(), ","));
    }
}
