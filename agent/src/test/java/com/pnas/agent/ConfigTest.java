package com.pnas.agent;

import com.pnas.agent.config.AgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
