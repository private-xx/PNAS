package com.pnas.agent;

import com.pnas.agent.config.AgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** init/status 子命令:不再是静默 no-op。 */
class MainTest {

    @TempDir
    Path dir;

    @Test
    void initWritesLoadableConfigWithDefaults() throws Exception {
        Path configFile = dir.resolve("nested/config.json");
        Path root = Files.createDirectories(dir.resolve("data"));

        int code = Main.run(new String[] {"init",
            "--server", "http://nas.local:8080",
            "--token", "tok",
            "--device", "dev-1",
            "--root", root + ":photos",
            "--config", configFile.toString()});

        assertEquals(0, code);
        assertTrue(Files.exists(configFile), "init 必须写出配置文件");
        AgentConfig loaded = AgentConfig.load(configFile);
        loaded.validate();
        assertEquals("http://nas.local:8080", loaded.serverUrl());
        assertEquals("tok", loaded.token());
        assertEquals("dev-1", loaded.deviceId());
        assertEquals(360, loaded.intervalMinutes());
        assertTrue(loaded.realtime());
        assertEquals(List.of(), loaded.exclude());
        assertEquals(3, loaded.maxRetries());
        assertEquals(500L, loaded.retryBackoffMs());
        assertEquals(1, loaded.roots().size());
        assertEquals(root.toString(), loaded.roots().get(0).path());
        assertEquals("photos", loaded.roots().get(0).remoteName());
    }

    @Test
    void initAcceptsRootWithoutRemoteName() throws Exception {
        Path configFile = dir.resolve("plain.json");
        Path root = Files.createDirectories(dir.resolve("plain"));

        assertEquals(0, Main.run(new String[] {"init",
            "--server", "http://nas.local:8080", "--token", "tok", "--device", "dev-1",
            "--root", root.toString(), "--config", configFile.toString()}));
        assertNull(AgentConfig.load(configFile).roots().get(0).remoteName());
    }

    @Test
    void statusLoadsConfigAndQueriesLatestRun() throws Exception {
        try (StubServer stub = new StubServer()) {
            Path configFile = dir.resolve("status.json");
            new AgentConfig(stub.baseUrl(), "t", "dev-1",
                List.of(new AgentConfig.Root(dir.toString(), "r")), 360, true,
                List.of(), 3, 1L).save(configFile);

            int code = Main.run(new String[] {"status", "--config", configFile.toString()});

            assertEquals(0, code);
            assertEquals(1, stub.runsListed.get(), "status 应查询最近一条运行记录");
        }
    }
}
