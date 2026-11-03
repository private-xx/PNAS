package com.pnas.agent;

import com.pnas.agent.config.AgentConfig;
import com.pnas.agent.remote.HttpJson;
import com.pnas.agent.remote.ManifestClient;
import com.pnas.agent.remote.NodeClient;
import com.pnas.agent.remote.RunClient;
import com.pnas.agent.remote.UploadClient;
import com.pnas.agent.run.RunMode;
import com.pnas.agent.run.Runner;
import com.pnas.agent.scan.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerTest {

    private static final int CHUNK_SIZE = 4 * 1024 * 1024;

    @TempDir
    Path dir;

    private Runner runner(StubServer stub) {
        var http = new HttpJson(stub.baseUrl(), "t");
        var cfg = new AgentConfig(stub.baseUrl(), "t", "dev-1",
            List.of(new AgentConfig.Root(dir.toString(), "r")), 60, false, List.of(), 3, 100);
        return new Runner(cfg, new Scanner(), new com.pnas.agent.plan.Planner(),
            new ManifestClient(http), new UploadClient(http), new NodeClient(http),
            new RunClient(http), CHUNK_SIZE);
    }

    @Test
    void firstRunUploadsAndSecondRunSkips() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "aaa");
        Files.writeString(dir.resolve("b.txt"), "bbbb");
        try (StubServer stub = new StubServer()) {
            Runner runner = runner(stub);

            Runner.Stats first = runner.run(RunMode.MANUAL);
            assertEquals(2, first.seen(), "首轮应看到 2 个文件");
            assertEquals(2, first.uploaded(), "首轮应上传 2 个文件");
            assertEquals(0, first.skipped(), "首轮不应跳过");
            assertEquals(0, first.failed(), "首轮不应失败");
            assertEquals(7L, first.bytes(), "上传字节数应为两个文件大小之和");
            assertTrue(stub.uploadedPaths.containsAll(List.of("a.txt", "b.txt")),
                "应上传 a.txt 与 b.txt,实际: " + stub.uploadedPaths);
            assertEquals(1, stub.startedRuns.size(), "每轮都应启动一次运行记录");

            Runner.Stats second = runner.run(RunMode.MANUAL);
            assertEquals(2, second.seen());
            assertEquals(0, second.uploaded(), "次轮不应重复上传");
            assertEquals(2, second.skipped(), "次轮两个文件都应跳过");
            assertEquals(0, second.failed());
            assertEquals(0, second.bytes());

            assertEquals(2, stub.finishedRuns.size(), "两轮都应结束运行记录");
        }
    }

    @Test
    void changedFileUploadsAgainAndIsCounted() throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "aaa");
        try (StubServer stub = new StubServer()) {
            Runner runner = runner(stub);

            Runner.Stats first = runner.run(RunMode.MANUAL);
            assertEquals(1, first.uploaded());

            Files.writeString(file, "aaaaaa");
            Files.setLastModifiedTime(file,
                FileTime.fromMillis(System.currentTimeMillis() + 10_000));

            Runner.Stats second = runner.run(RunMode.MANUAL);
            assertEquals(1, second.seen());
            assertEquals(1, second.uploaded(), "内容与 mtime 变化后应重新上传");
            assertEquals(0, second.skipped(), "变化文件不应被跳过");
            assertEquals(0, second.failed());

            assertEquals(2, stub.finishedRuns.size());
        }
    }
}
