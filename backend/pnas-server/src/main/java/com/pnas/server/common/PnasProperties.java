package com.pnas.server.common;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pnas")
public record PnasProperties(
        Path dataDir,
        Security security,
        Upload upload,
        Bootstrap bootstrap) {
    public record Security(boolean requireTls) {}
    public record Upload(int chunkSize) {}
    public record Bootstrap(String adminUsername, String adminPassword) {}
}
