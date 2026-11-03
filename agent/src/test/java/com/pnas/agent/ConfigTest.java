package com.pnas.agent;

import com.pnas.agent.config.AgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

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
    void saveSupportsRelativeFileWithoutParent() throws Exception {
        var cfg = new AgentConfig("http://nas.local:8080", "tok", "dev-1",
            List.of(new AgentConfig.Root("/tmp/a", "a")), 360, true,
            List.of("**/.git/**"), 3, 500L);
        Path relative = Path.of("config-no-parent-" + UUID.randomUUID() + ".json");
        try {
            cfg.save(relative);
            assertTrue(Files.exists(relative));
            assertEquals(cfg, AgentConfig.load(relative));
        } finally {
            Files.deleteIfExists(relative);
        }
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
