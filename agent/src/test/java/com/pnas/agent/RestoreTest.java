package com.pnas.agent;

import com.pnas.agent.remote.HttpJson;
import com.pnas.agent.remote.ManifestClient;
import com.pnas.agent.remote.NodeClient;
import com.pnas.agent.restore.Restorer;
import com.pnas.agent.restore.Verifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
            assertThrows(IllegalArgumentException.class,
                () -> restorer.restore("dev-1", "../etc/passwd", dir, false));
            assertThrows(IllegalArgumentException.class,
                () -> restorer.restore("dev-1", "Docs/../../etc/passwd", dir, false));
            assertThrows(IllegalArgumentException.class,
                () -> restorer.restore("dev-1", "/etc/passwd", dir, false));
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
