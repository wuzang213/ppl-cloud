package com.hmdp.search.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 契约测试用的文本抽取工具。
 * <p>
 * 为什么要专门做一层：直接对**整份源码文本**跑正则会被**注释**里的示例名命中
 * （例如 {@code EsIndexNames} 的注释里就写着"完整名形如 hmdp_blog_v2"）。
 * 契约的本意是「**代码里**不得出现物理索引名字面量」，所以只扫**字符串字面量**才准确 ——
 * 注释里提到它反而是好事（说明知识被记录下来了）。
 */
public final class TextScan {

    private TextScan() {
    }

    /**
     * 抽取 Java 源码里的字符串字面量内容（不含两侧引号；转义序列原样保留、不做还原）。
     * <p>
     * 刻意不处理注释：本项目源码注释里的示例名都不带 ASCII 双引号（用的是 {@code {@code ...}}
     * 或中文引号），所以"注释被误扫"的情形不会发生。若将来注释里出现带双引号的索引名，
     * 这条简化假设会被打破 —— 届时改为先剥离注释再抽取。
     */
    public static List<String> stringLiterals(String javaSource) {
        List<String> literals = new ArrayList<>();
        int i = 0;
        while (i < javaSource.length()) {
            if (javaSource.charAt(i) == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < javaSource.length() && javaSource.charAt(i) != '"') {
                    if (javaSource.charAt(i) == '\\') {
                        i++;
                        if (i >= javaSource.length()) {
                            break;
                        }
                    }
                    sb.append(javaSource.charAt(i));
                    i++;
                }
                literals.add(sb.toString());
            }
            i++;
        }
        return literals;
    }

    /** 去掉 XML 注释，用于对 pom 做"是否声明了某依赖"这类断言 */
    public static String stripXmlComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }
}
