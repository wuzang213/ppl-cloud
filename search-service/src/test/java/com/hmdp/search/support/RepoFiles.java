package com.hmdp.search.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 结构契约测试用的仓库文件定位工具。
 * <p>
 * 契约测试要读的是**仓库里**的文件（源码 / yaml / pom / mapping JSON），
 * 而 Maven Surefire 的工作目录是「模块目录」（即 {@code search-service/}），
 * IDE 里跑又可能是「仓库根」。这里统一向上找，两种情形都能命中，
 * 避免把断言写成"路径写死 → 假红"。
 */
public final class RepoFiles {

    private static final Path REPO_ROOT = locateRepoRoot();
    private static final Path MODULE_DIR = REPO_ROOT.resolve("search-service");

    private RepoFiles() {
    }

    private static Path locateRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("search-service").resolve("pom.xml"))
                    && Files.isRegularFile(dir.resolve("gateway-service").resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("无法定位仓库根目录（需同时存在 search-service/ 与 gateway-service/），cwd="
                + Path.of("").toAbsolutePath());
    }

    public static Path repoRoot() {
        return REPO_ROOT;
    }

    public static Path moduleDir() {
        return MODULE_DIR;
    }

    /** 模块内相对路径，如 {@code src/main/resources/application.yaml} */
    public static Path moduleFile(String relative) {
        return MODULE_DIR.resolve(relative);
    }

    /** 仓库内相对路径，如 {@code gateway-service/src/main/resources/application.yaml} */
    public static Path repoFile(String relative) {
        return REPO_ROOT.resolve(relative);
    }

    public static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败: " + file, e);
        }
    }

    /** search-service 的全部生产源码 */
    public static List<Path> mainJavaSources() {
        Path root = MODULE_DIR.resolve("src/main/java");
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("遍历源码失败: " + root, e);
        }
    }

    /** 把 {@code a/b/C.java} 形式的模块内相对路径读成源码文本 */
    public static String readMainJava(String moduleRelativePath) {
        return read(moduleFile("src/main/java/" + moduleRelativePath));
    }

    /** classpath 上的资源（走模块 resources 目录读，避免依赖类加载器缓存） */
    public static String readMainResource(String moduleRelativePath) {
        return read(moduleFile("src/main/resources/" + moduleRelativePath));
    }
}
