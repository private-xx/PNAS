package com.pnas.agent.scan;

import com.pnas.agent.model.FileItem;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 递归扫描一个根目录。不跟随符号链接,跳过符号链接与非普通文件;
 * 排除 glob 以相对根目录的路径匹配。
 */
public final class Scanner {

    /** 计算首末块哈希时使用的块大小。 */
    public static final int CHUNK_SIZE = 4 * 1024 * 1024;

    public List<FileItem> scan(Path root, String remoteName, List<String> excludes, int chunkSize)
            throws IOException {
        List<PathMatcher> matchers = compile(excludes);
        List<FileItem> items = new ArrayList<>();
        Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (excluded(matchers, relativize(root, dir), true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String relUnderRoot = relativize(root, file);
                if (excluded(matchers, relUnderRoot, false)) {
                    return FileVisitResult.CONTINUE;
                }
                String relPath = remoteName == null || remoteName.isBlank()
                    ? relUnderRoot
                    : remoteName + "/" + relUnderRoot;
                String[] hashes = ChunkHashes.firstAndLast(file, chunkSize);
                items.add(new FileItem(relPath, file, attrs.size(),
                    attrs.lastModifiedTime().toInstant(), hashes[0], hashes[1]));
                return FileVisitResult.CONTINUE;
            }
        });
        return items;
    }

    private static List<PathMatcher> compile(List<String> excludes) {
        List<PathMatcher> matchers = new ArrayList<>();
        if (excludes == null) {
            return matchers;
        }
        for (String glob : excludes) {
            if (glob == null || glob.isBlank()) {
                continue;
            }
            addMatcher(glob, matchers);
            // JDK 的 glob 中 `**/` 至少要吃掉一级目录,根目录下的 `.git` 等匹配不到;
            // 因此对以 `**/` 开头的模式再编译一份去掉前缀的变体。
            if (glob.startsWith("**/")) {
                addMatcher(glob.substring(3), matchers);
            }
        }
        return matchers;
    }

    private static void addMatcher(String glob, List<PathMatcher> matchers) {
        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
    }

    private static boolean excluded(List<PathMatcher> matchers, String rel, boolean directory) {
        if (matchers.isEmpty()) {
            return false;
        }
        Path path = Path.of(rel);
        Path child = Path.of(rel + "/placeholder");
        for (PathMatcher m : matchers) {
            if (m.matches(path)) {
                return true;
            }
            // 目录型 glob(如 **/.git/**)不匹配目录自身,用占位子路径探测以便整棵子树剪枝。
            if (directory && m.matches(child)) {
                return true;
            }
        }
        return false;
    }

    private static String relativize(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }
}
