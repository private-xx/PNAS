package com.pnas.agent;

import com.pnas.agent.config.AgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    @TempDir
    Path dir;

    @Test
    void saveAndLoadRoundTrip() throws Exception {
        var cfg = new AgentConfig("http://nas.local:8080", "tok", "dev-1",
            List.of(new AgentConfig.Root("/tmp/a", "a")), 360, true,
            List.of("**/.git/**"), 3, 500L);
        Path file = dir.resolve("config.json");
        cfg.save(file);
        AgentConfig loaded = AgentConfig.load(file);
        assertEquals(cfg, loaded);
    }

    @Test
    void missingRealtimeDefaultsToTrue() throws Exception {
        Path file = dir.resolve("no-realtime.json");
        Files.writeString(file, """
            {
              "serverUrl": "http://nas.local:8080",
              "token": "tok",
              "deviceId": "dev-1",
              "roots": [{"path": "/tmp/a", "remoteName": "a"}],
              "intervalMinutes": 360,
              "exclude": ["**/.git/**"],
              "maxRetries": 3,
              "retryBackoffMs": 500
            }
            """);
        assertTrue(AgentConfig.load(file).realtime(), "省略 realtime 键应默认为 true");
    }

    @Test
    void validateRejectsMissingFields() {
        var missingServerUrl = assertThrows(IllegalArgumentException.class,
            () -> new AgentConfig("", "t", "d",
                List.of(new AgentConfig.Root("/tmp/a", "a")), 60, false, List.of(), 3, 100L).validate());
        assertTrue(missingServerUrl.getMessage().contains("serverUrl"));

        var missingToken = assertThrows(IllegalArgumentException.class,
            () -> new AgentConfig("http://x", "", "d",
                List.of(new AgentConfig.Root("/tmp/a", "a")), 60, false, List.of(), 3, 100L).validate());
        assertTrue(missingToken.getMessage().contains("token"));

        var missingRoots = assertThrows(IllegalArgumentException.class,
            () -> new AgentConfig("http://x", "t", "d",
                List.of(), 60, false, List.of(), 3, 100L).validate());
        assertTrue(missingRoots.getMessage().contains("roots"));
    }

    @Test
    void validateRejectsNonPositiveMaxRetries() {
        var zero = assertThrows(IllegalArgumentException.class,
            () -> new AgentConfig("http://x", "t", "d",
                List.of(new AgentConfig.Root("/tmp/a", "a")), 60, false, List.of(), 0, 100L).validate());
        assertTrue(zero.getMessage().contains("maxRetries"));

        var negative = assertThrows(IllegalArgumentException.class,
            () -> new AgentConfig("http://x", "t", "d",
                List.of(new AgentConfig.Root("/tmp/a", "a")), 60, false, List.of(), -1, 100L).validate());
        assertTrue(negative.getMessage().contains("maxRetries"));
    }

    @Test
    void validateRejectsBlankRootPath() {
        var blank = assertThrows(IllegalArgumentException.class,
            () -> new AgentConfig("http://x", "t", "d",
                List.of(new AgentConfig.Root("  ", "a")), 60, false, List.of(), 3, 100L).validate());
        assertTrue(blank.getMessage().contains("path"));

        var nullPath = assertThrows(IllegalArgumentException.class,
            () -> new AgentConfig("http://x", "t", "d",
                List.of(new AgentConfig.Root(null, "a")), 60, false, List.of(), 3, 100L).validate());
        assertTrue(nullPath.getMessage().contains("path"));
    }

    @Test
    void saveSupportsNestedRelativePathWithoutPreExistingParent() throws Exception {
        var cfg = new AgentConfig("http://nas.local:8080", "tok", "dev-1",
            List.of(new AgentConfig.Root("/tmp/a", "a")), 360, true,
            List.of("**/.git/**"), 3, 500L);
        Path nested = dir.resolve("nested/config.json");
        assertTrue(Files.notExists(nested.getParent()));
        cfg.save(nested);
        assertTrue(Files.exists(nested));
        assertEquals(cfg, AgentConfig.load(nested));
    }

    @Test
    void saveCreatesMissingParentDirectories() {
        var cfg = new AgentConfig("http://nas.local:8080", "tok", "dev-1",
            List.of(new AgentConfig.Root("/tmp/a", "a")), 360, true,
            List.of("**/.git/**"), 3, 500L);
        Path nested = dir.resolve("a/b/config.json");
        cfg.save(nested);
        assertTrue(Files.exists(nested));
        assertEquals(cfg, AgentConfig.load(nested));
    }
}
