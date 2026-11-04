package com.pnas.agent;

import com.pnas.agent.remote.HttpJson;
import com.pnas.agent.remote.ManifestClient;
import com.pnas.agent.remote.NodeClient;
import com.pnas.agent.restore.Restorer;
import com.pnas.agent.restore.Verifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreTest {

    private static final int CHUNK_SIZE = 4 * 1024 * 1024;

    @TempDir
    Path dir;

    private Restorer restorer(StubServer stub) {
        var http = new HttpJson(stub.baseUrl(), "t");
        return new Restorer(new ManifestClient(http), new NodeClient(http), CHUNK_SIZE);
    }

    private Verifier verifier(StubServer stub) {
        var http = new HttpJson(stub.baseUrl(), "t");
        return new Verifier(new ManifestClient(http), new NodeClient(http), CHUNK_SIZE);
    }

    @Test
    void restoreWritesFileAndVerifiesManifestHash() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.seedFile("Docs/a.txt", "abc");
            Path out = dir.resolve("out");

            int count = restorer(stub).restore("dev-1", "Docs/a.txt", out, false);

            assertEquals(1, count, "应恢复 1 个文件");
            assertEquals("abc", Files.readString(out.resolve("a.txt")),
                "恢复内容应与远端一致(首段远程名被剥离)");
        }
    }

    @Test
    void restoreDirectoryWritesAllFiles() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.seedFile("Docs/a.txt", "aaa");
            stub.seedFile("Docs/sub/b.txt", "bbbb");
            Path out = dir.resolve("out");

            int count = restorer(stub).restore("dev-1", "Docs", out, true);

            assertEquals(2, count, "目录恢复应包含 2 个文件");
            assertEquals("aaa", Files.readString(out.resolve("a.txt")));
            assertEquals("bbbb", Files.readString(out.resolve("sub/b.txt")));
        }
    }

    @Test
    void skipsExistingFileWithoutOverwriteButCountsIt() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.seedFile("Docs/a.txt", "abc");
            Path out = dir.resolve("out");
            Files.createDirectories(out);
            Files.writeString(out.resolve("a.txt"), "old");

            int count = restorer(stub).restore("dev-1", "Docs/a.txt", out, false);

            assertEquals(1, count, "已存在文件也应计入恢复数");
            assertEquals("old", Files.readString(out.resolve("a.txt")), "未指定 overwrite 时不应覆盖");

            restorer(stub).restore("dev-1", "Docs/a.txt", out, true);
            assertEquals("abc", Files.readString(out.resolve("a.txt")), "overwrite 时应覆盖");
        }
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        try (StubServer stub = new StubServer()) {
            Restorer restorer = restorer(stub);
            for (String bad : List.of(
                    "../etc/passwd",
                    "Docs/../../etc/passwd",
                    "/etc/passwd",
                    "..\\..\\win.txt",
                    "C:\\temp\\x.txt")) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> restorer.restore("dev-1", bad, dir, false), "应拒绝非法路径: " + bad);
                assertTrue(e.getMessage().contains("非法相对路径"),
                    "异常信息应说明非法相对路径: " + bad + " → " + e.getMessage());
            }
        }
    }

    @Test
    void rejectsSymlinkPrefixInDestination() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.seedFile("Docs/link/x.txt", "abc");
            Path out = dir.resolve("out");
            Path outside = dir.resolve("outside");
            Files.createDirectories(out);
            Files.createDirectories(outside);
            Files.createSymbolicLink(out.resolve("link"), outside);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> restorer(stub).restore("dev-1", "Docs/link/x.txt", out, true));

            assertTrue(e.getMessage().contains("目标包含符号链接"),
                "异常信息应说明符号链接: " + e.getMessage());
            assertFalse(Files.exists(outside.resolve("x.txt")),
                "不得经符号链接写到目标目录之外");
        }
    }

    @Test
    void rejectsDanglingSymlinkTarget() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.seedFile("Docs/a.txt", "abc");
            Path out = dir.resolve("out");
            Files.createDirectories(out);
            Path missing = dir.resolve("missing.txt");
            Files.createSymbolicLink(out.resolve("a.txt"), missing);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> restorer(stub).restore("dev-1", "Docs/a.txt", out, false));

            assertTrue(e.getMessage().contains("目标包含符号链接"),
                "悬空符号链接目标也应拒绝: " + e.getMessage());
            assertFalse(Files.exists(missing, LinkOption.NOFOLLOW_LINKS),
                "不得经悬空符号链接创建文件");
        }
    }

    @Test
    void rejectsSymlinkDestinationRoot() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.seedFile("Docs/a.txt", "abc");
            Path real = dir.resolve("real");
            Files.createDirectories(real);
            Path linkRoot = dir.resolve("link-root");
            Files.createSymbolicLink(linkRoot, real);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> restorer(stub).restore("dev-1", "Docs/a.txt", linkRoot, false));

            assertTrue(e.getMessage().contains("符号链接"),
                "目标根目录为符号链接时应拒绝: " + e.getMessage());
            assertFalse(Files.exists(real.resolve("a.txt")), "不得写入符号链接指向的目录");
        }
    }

    @Test
    void rejectsExistingDirectoryAtTargetPath() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.seedFile("Docs/a.txt", "abc");
            Path out = dir.resolve("out");
            Files.createDirectories(out.resolve("a.txt"));

            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> restorer(stub).restore("dev-1", "Docs/a.txt", out, false));

            assertTrue(e.getMessage().contains("目标已存在且不是普通文件"),
                "已存在目录目标应拒绝: " + e.getMessage());
        }
    }

    @Test
    void restoresEmptyFileWithZeroBytes() throws Exception {
        String relPath = "Docs/empty-" + UUID.randomUUID() + ".txt";
        try (StubServer stub = new StubServer()) {
            stub.seedFile(relPath, "");
            Path out = dir.resolve("out");

            int count = restorer(stub).restore("dev-1", relPath, out, false);

            assertEquals(1, count, "空文件也应计为恢复 1 个文件");
            Path restored = out.resolve(relPath.substring("Docs/".length()));
            assertTrue(Files.exists(restored), "空文件应被写出");
            assertEquals(0, Files.size(restored), "空文件应写入 0 字节");
            assertEquals("", Files.readString(restored), "空文件内容应为空");

            Verifier.VerifyResult result = verifier(stub).verify("dev-1", 0);
            assertEquals(1, result.checked(), "空文件应参与校验");
            assertEquals(1, result.passed(), "空文件的清单哈希应校验通过");
            assertTrue(result.failed().isEmpty(), "空文件不应进入失败清单: " + result.failed());
        }
    }

    @Test
    void rejectsUnknownPath() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.seedFile("Docs/a.txt", "abc");
            assertThrows(IllegalArgumentException.class,
                () -> restorer(stub).restore("dev-1", "Nope", dir, false));
        }
    }

    @Test
    void hashMismatchThrowsAndWritesNothing() throws Exception {
        try (StubServer stub = new StubServer()) {
            UUID nodeId = stub.seedFile("Docs/a.txt", "abc");
            stub.corrupt(nodeId);
            Path out = dir.resolve("out");

            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> restorer(stub).restore("dev-1", "Docs/a.txt", out, false));

            assertTrue(e.getMessage().contains("哈希校验失败"), "异常信息应说明哈希校验失败: " + e.getMessage());
            assertFalse(Files.exists(out.resolve("a.txt")), "校验失败时不应写出文件");
        }
    }

    @Test
    void restoreMultiChunkFileMatchesChunkedManifestHash() throws Exception {
        String content = "x".repeat(4 * 1024 * 1024 + 16);
        try (StubServer stub = new StubServer()) {
            stub.seedFile("Files/big.bin", content);
            Path out = dir.resolve("out");

            int count = restorer(stub).restore("dev-1", "Files/big.bin", out, false);

            assertEquals(1, count);
            assertEquals(content, Files.readString(out.resolve("big.bin")),
                "跨 4 MiB 分块边界的文件也应按分块哈希规则校验通过");
        }
    }

    @Test
    void verifyReportsPassedAndFailedFiles() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.seedFile("Docs/a.txt", "aaa");
            UUID bad = stub.seedFile("Docs/b.txt", "bbbb");
            stub.corrupt(bad);

            Verifier.VerifyResult all = verifier(stub).verify("dev-1", 0);

            assertEquals(2, all.checked(), "应检查全部文件");
            assertEquals(1, all.passed(), "应只有 1 个文件校验通过");
            assertEquals(1, all.failed().size(), "应记录 1 个失败文件");
            assertTrue(all.failed().contains("Docs/b.txt"), "失败清单应包含受损文件: " + all.failed());

            Verifier.VerifyResult sample = verifier(stub).verify("dev-1", 1);
            assertEquals(1, sample.checked(), "limit 应限制检查数量");
        }
    }
}
