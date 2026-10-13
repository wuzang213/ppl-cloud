package com.hmdp.search.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高亮片段的 XSS 边界（免登录搜索接口的返回体直接进前端 {@code v-html}）。
 * <p>
 * 必须钉住两件事：<b>用户文本里的标签一定被转义</b>、<b>占位符一定被还原成 {@code <em>}</b>，
 * 且顺序是「先整体转义 → 再还原占位符」。
 */
class HighlightSanitizerTest {

    @Test
    @DisplayName("XSS：<img src=x onerror=...> 必须被转义，不能原样回吐")
    void dangerousTagIsEscaped() {
        String out = HighlightSanitizer.safeHighlight("<img src=x onerror=alert(1)>", null);
        assertFalse(out.contains("<img"), "危险标签被原样返回，前端 v-html 会执行: " + out);
        assertTrue(out.contains("&lt;img"), "应转义成 &lt;img，实际: " + out);
        assertTrue(out.contains("&gt;"), "右尖括号也应被转义，实际: " + out);
    }

    @Test
    @DisplayName("高亮：占位符被还原成 <em>...</em>")
    void placeholderIsRestoredToEm() {
        String fragment = HighlightSanitizer.HL_PRE + "火锅" + HighlightSanitizer.HL_POST + "店";
        assertEquals("<em>火锅</em>店", HighlightSanitizer.safeHighlight(fragment, null));
    }

    @Test
    @DisplayName("顺序：先转义再还原 —— 危险标签转义了，<em> 仍高亮")
    void escapeComesBeforeRestore() {
        String fragment = HighlightSanitizer.HL_PRE + "<script>alert(1)</script>" + HighlightSanitizer.HL_POST;
        String out = HighlightSanitizer.safeHighlight(fragment, null);
        assertEquals("<em>&lt;script&gt;alert(1)&lt;/script&gt;</em>", out);
    }

    @Test
    @DisplayName("顺序反了就会露馅：用户原文里的 <em> 必须变成 &lt;em&gt;")
    void userAuthoredEmIsEscaped() {
        // 这条是「顺序不能反」的反向证据：如果实现写成"先还原占位符再转义"，
        // 那么占位符还原出来的 <em> 也会被转义成 &lt;em&gt;，高亮失效；
        // 反之若"完全不转义"，用户自己写的 <em> 就会被当成高亮。
        // 两种错法都逃不过本断言。
        assertEquals("&lt;em&gt;假的&lt;/em&gt;", HighlightSanitizer.safeHighlight("<em>假的</em>", null));
    }

    @Test
    @DisplayName("& 必须第一个替换（否则生成 &amp;lt;）")
    void ampersandIsReplacedFirst() {
        assertEquals("A &amp; B", HighlightSanitizer.escapeHtml("A & B"));
        // 输入里本来就有 &lt; 时，& 应被转义成 &amp;lt;（只转一次，不是两次）
        assertEquals("A &amp;lt;", HighlightSanitizer.escapeHtml("A &lt;"));
        assertEquals("&amp;amp;", HighlightSanitizer.escapeHtml("&amp;"));
    }

    @Test
    @DisplayName("双引号与单引号都要转义（属性上下文注入）")
    void quotesAreEscaped() {
        assertEquals("&quot;", HighlightSanitizer.escapeHtml("\""));
        assertEquals("&#39;", HighlightSanitizer.escapeHtml("'"));
        assertEquals("onmouseover=&quot;x&quot;", HighlightSanitizer.escapeHtml("onmouseover=\"x\""));
    }

    @Test
    @DisplayName("fragment 为 null 时用 fallback，且 fallback 同样被转义")
    void nullFragmentFallsBackToEscapedFallback() {
        assertEquals("标题", HighlightSanitizer.safeHighlight(null, "标题"));
        assertEquals("&lt;b&gt;标题&lt;/b&gt;", HighlightSanitizer.safeHighlight(null, "<b>标题</b>"));
    }

    @Test
    @DisplayName("fragment 非 null 时忽略 fallback（不拼接）")
    void fragmentWinsOverFallback() {
        assertEquals("<em>命中</em>", HighlightSanitizer.safeHighlight(
                HighlightSanitizer.HL_PRE + "命中" + HighlightSanitizer.HL_POST, "原始标题"));
    }

    @Test
    @DisplayName("两者都为 null 时返回 null（不是空串）")
    void bothNullReturnsNull() {
        assertNull(HighlightSanitizer.safeHighlight(null, null));
    }

    @Test
    @DisplayName("空串 fragment 返回空串（fragment!=null 优先于 fallback）")
    void emptyFragmentIsKept() {
        assertEquals("", HighlightSanitizer.safeHighlight("", "兜底"));
    }

    @Test
    @DisplayName("占位符本身不含 < >，才能安全交给 ES 当 pre/post_tags")
    void placeholderIsNotHtml() {
        assertFalse(HighlightSanitizer.HL_PRE.contains("<"));
        assertFalse(HighlightSanitizer.HL_PRE.contains(">"));
        assertFalse(HighlightSanitizer.HL_POST.contains("<"));
        assertFalse(HighlightSanitizer.HL_POST.contains(">"));
        assertEquals("[[hmdp-hl]]", HighlightSanitizer.HL_PRE);
        assertEquals("[[/hmdp-hl]]", HighlightSanitizer.HL_POST);
    }

    @Test
    @DisplayName("已知取舍：用户正文里恰好写出占位符时会被高亮（形态刻意做得不像正常文本）")
    void placeholderCollisionIsAccepted() {
        // 这不是缺陷而是取舍：占位符形态 [[hmdp-hl]] 在中文笔记正文里几乎不可能自然出现，
        // 用它换掉了"必须维护一份 HTML 白名单"的复杂度。用断言把这个取舍钉在明处。
        assertEquals("<em>我手写的</em>",
                HighlightSanitizer.safeHighlight("[[hmdp-hl]]我手写的[[/hmdp-hl]]", null));
    }

    @Test
    @DisplayName("href / 事件属性等常见 payload 都被打断（标签被转义成文本，不再是元素）")
    void commonPayloadsAreNeutralised() {
        String out = HighlightSanitizer.safeHighlight("<a href=\"javascript:alert(1)\" onclick='x'>点</a>", null);
        // 关键不是"删掉 javascript:"（转义不做删改），而是**标签被转义成文本**：
        // 浏览器按纯文本渲染，href / onclick 不再是属性，`javascript:` 只是一串普通字符。
        assertEquals("&lt;a href=&quot;javascript:alert(1)&quot; onclick=&#39;x&#39;&gt;点&lt;/a&gt;", out);
        assertTrue(out.startsWith("&lt;a "), out);
        assertFalse(out.contains("<a "), out);
        assertFalse(out.contains("<a>"), out);
        assertTrue(out.contains("&quot;"), out);
        assertTrue(out.contains("&#39;"), out);
    }
}
