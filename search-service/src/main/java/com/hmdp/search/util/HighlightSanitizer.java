package com.hmdp.search.util;

/** 高亮片段的 HTML 安全化。从 {@link com.hmdp.search.service.impl.SearchQueryServiceImpl} 原样搬出（含两个占位符常量），行为一字不变，仅从 {@code private} 提升为 {@code public} 以便离线单测覆盖 —— 这是 XSS 边界，必须被断言看住。
 * ⚠️ 不能让 ES 直接插入 {@code <em>}：ES 把 {@code pre_tags}/{@code post_tags} 原样插进用户可写的文档原文（{@code title}/{@code content}/{@code name}），前端为渲染高亮就必须用 {@code v-html}，发布者在标题写 {@code <img src=x onerror=...>} 即成存储型 XSS。处理顺序不可反：先插占位符 → 整体转义 → 再换回 {@code <em>}（反了会把 {@code <em>} 转成 {@code &lt;em&gt;}，高亮失效）。 */
public final class HighlightSanitizer {

    /** 交给 ES 当 {@code pre_tags} 的占位符（正文里不可能出现的形态） */
    public static final String HL_PRE = "[[hmdp-hl]]";

    /** 交给 ES 当 {@code post_tags} 的占位符 */
    public static final String HL_POST = "[[/hmdp-hl]]";

    /** 还原后的真正高亮标签 */
    public static final String HL_EM_OPEN = "<em>";

    public static final String HL_EM_CLOSE = "</em>";

    private HighlightSanitizer() {
    }

    /** 把 ES 高亮片段转成"HTML 安全片段"：先整体转义，再把占位符还原成 {@code <em>}。返回体里的 {@code titleHighlight}/{@code nameHighlight}/{@code snippet} 都经过这里，故前端可放心 {@code v-html}（{@code title}/{@code name}/{@code content} 等原始字段仍是纯文本，请用插值渲染）。
     * @param fragment ES 返回的高亮片段，可能为 {@code null}；@param fallback 片段为空时的兜底原文（如标题），同样会被转义、可为 {@code null}；@return HTML 安全片段，两者都为空时返回 {@code null}。 */
    public static String safeHighlight(String fragment, String fallback) {
        String text = fragment != null ? fragment : fallback;
        if (text == null) {
            return null;
        }
        return escapeHtml(text)
                .replace(HL_PRE, HL_EM_OPEN)
                .replace(HL_POST, HL_EM_CLOSE);
    }

    /** 最小必要集的 HTML 转义。⚠️ {@code &} 必须第一个替换，否则会把后面生成的 {@code &lt;} 里的 {@code &} 再转一次、变成 {@code &amp;lt;}。 */
    public static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
