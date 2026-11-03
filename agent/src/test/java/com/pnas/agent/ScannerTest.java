package com.pnas.agent;

import com.pnas.agent.model.FileItem;
import com.pnas.agent.scan.ChunkHashes;
import com.pnas.agent.scan.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScannerTest {

    private static final int CHUNK = 4 * 1024 * 1024;

    @TempDir
    Path dir;

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static FileItem bySuffix(List<FileItem> items, String suffix) {
        return items.stream()
            .filter(i -> i.relPath().endsWith(suffix))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing item ending with " + suffix + " in " + items));
    }

    @Test
    void scansRelativePathsAndSkipsExcludedAndSymlinks() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "aaa");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/b.txt"), "bbb");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/ignored.txt"), "x");
        var link = dir.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, dir.resolve("a.txt"));
        } catch (Exception ignored) {
            // 文件系统不支持符号链接时,该断言自然退化
        }

        var items = new Scanner().scan(dir, "root", List.of("**/.git/**"), CHUNK);

        List<String> rels = items.stream().map(FileItem::relPath).toList();
        assertTrue(rels.contains("root/a.txt"), rels.toString());
        assertTrue(rels.contains("root/sub/b.txt"), rels.toString());
        assertFalse(rels.contains("root/.git/ignored.txt"), rels.toString());
        assertFalse(rels.contains("root/link.txt"), rels.toString());

        FileItem a = bySuffix(items, "a.txt");
        assertEquals(sha("aaa".getBytes()), a.firstChunkSha());
        assertEquals(sha("aaa".getBytes()), a.lastChunkSha());
        assertEquals(3L, a.size());
        assertEquals(dir.resolve("a.txt"), a.path());
        assertEquals(Files.getLastModifiedTime(dir.resolve("a.txt")).toInstant(), a.mtime());
    }

    @Test
    void normalizesTrailingSlashAndBlankRemoteName() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "aaa");

        var trailingSlash = new Scanner().scan(dir, "root/", List.of(), CHUNK);
        assertEquals(List.of("root/a.txt"),
            trailingSlash.stream().map(FileItem::relPath).toList());

        var blank = new Scanner().scan(dir, "  ", List.of(), CHUNK);
        assertEquals(List.of("a.txt"),
            blank.stream().map(FileItem::relPath).toList());
    }

    @Test
    void skipsSymlinkedDirectories() throws Exception {
        Files.writeString(dir.resolve("real.txt"), "real");
        Path link = dir.resolve("linked");
        try {
            Files.createSymbolicLink(link, dir);
        } catch (Exception e) {
            return; // 文件系统不支持符号链接
        }

        var items = new Scanner().scan(dir, "root", List.of(), CHUNK);

        List<String> rels = items.stream().map(FileItem::relPath).toList();
        assertTrue(rels.contains("root/real.txt"), rels.toString());
        assertFalse(rels.stream().anyMatch(r -> r.contains("linked")), rels.toString());
    }

    @Test
    void excludesAreMatchedAgainstPathsRelativeToScannedRoot() throws Exception {
        Files.writeString(dir.resolve("root.log"), "x");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/keep.log"), "x");
        Files.createDirectories(dir.resolve("cache/deep"));
        Files.writeString(dir.resolve("cache/deep/file.bin"), "x");

        var items = new Scanner().scan(dir, "", List.of("*.log", "**/cache/**"), CHUNK);

        List<String> rels = items.stream().map(FileItem::relPath).toList();
        assertFalse(rels.contains("root.log"), rels.toString());
        assertTrue(rels.contains("sub/keep.log"), rels.toString());
        assertFalse(rels.stream().anyMatch(r -> r.endsWith("file.bin")), rels.toString());
    }

    @Test
    void doesNotExcludeRegularFileWhoseNameLooksLikeExcludedDirectory() throws Exception {
        Files.writeString(dir.resolve("cache"), "keep-me");

        var items = new Scanner().scan(dir, "", List.of("**/cache/**"), CHUNK);

        List<String> rels = items.stream().map(FileItem::relPath).toList();
        assertTrue(rels.contains("cache"), rels.toString());
    }

    @Test
    void chunkHashesOfEmptyFileAreNull() throws Exception {
        Path empty = dir.resolve("empty.bin");
        Files.write(empty, new byte[0]);
        String[] hashes = ChunkHashes.firstAndLast(empty, CHUNK);
        assertNull(hashes[0]);
        assertNull(hashes[1]);
    }

    @Test
    void chunkHashesUseFirstAndLastChunk() throws Exception {
        byte[] content = new byte[CHUNK + 10];
        Arrays.fill(content, (byte) 7);
        Path file = dir.resolve("big.bin");
        Files.write(file, content);
        String[] hashes = ChunkHashes.firstAndLast(file, CHUNK);
        assertEquals(sha(Arrays.copyOfRange(content, 0, CHUNK)), hashes[0]);
        assertEquals(sha(Arrays.copyOfRange(content, CHUNK, content.length)), hashes[1]);
    }

    @Test
    void chunkHashesOfSingleChunkFileRepeatTheSameHash() throws Exception {
        byte[] small = "abc".getBytes();
        Path smallFile = dir.resolve("small.bin");
        Files.write(smallFile, small);
        String[] smallHashes = ChunkHashes.firstAndLast(smallFile, CHUNK);
        assertEquals(sha(small), smallHashes[0]);
        assertEquals(sha(small), smallHashes[1]);

        byte[] exact = new byte[CHUNK];
        Arrays.fill(exact, (byte) 3);
        Path exactFile = dir.resolve("exact.bin");
        Files.write(exactFile, exact);
        String[] exactHashes = ChunkHashes.firstAndLast(exactFile, CHUNK);
        assertEquals(sha(exact), exactHashes[0]);
        assertEquals(sha(exact), exactHashes[1]);
    }

    @Test
    void chunkHashesOfExactMultipleFileUseLastFullChunk() throws Exception {
        byte[] firstChunk = new byte[CHUNK];
        Arrays.fill(firstChunk, (byte) 11);
        byte[] secondChunk = new byte[CHUNK];
        Arrays.fill(secondChunk, (byte) 22);
        byte[] content = new byte[CHUNK * 2];
        System.arraycopy(firstChunk, 0, content, 0, CHUNK);
        System.arraycopy(secondChunk, 0, content, CHUNK, CHUNK);
        Path file = dir.resolve("two-chunks.bin");
        Files.write(file, content);

        String[] hashes = ChunkHashes.firstAndLast(file, CHUNK);

        assertEquals(sha(firstChunk), hashes[0]);
        assertEquals(sha(secondChunk), hashes[1]);
        // 旧公式 (size / chunkSize) * chunkSize 会把末块起点算到文件末尾,得到空串哈希。
        assertNotEquals(sha(new byte[0]), hashes[1]);
    }

    @Test
    void chunkHashesRejectNonPositiveChunkSize() throws Exception {
        Path file = dir.resolve("x.bin");
        Files.writeString(file, "x");
        assertThrows(IllegalArgumentException.class, () -> ChunkHashes.firstAndLast(file, 0));
        assertThrows(IllegalArgumentException.class, () -> ChunkHashes.firstAndLast(file, -1));
    }

    @Test
    void chunkHashesRejectMissingFile() {
        assertThrows(Exception.class, () -> ChunkHashes.firstAndLast(dir.resolve("nope.bin"), CHUNK));
    }
}
