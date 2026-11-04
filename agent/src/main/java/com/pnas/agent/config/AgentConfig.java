package com.pnas.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentConfig(String serverUrl, String token, String deviceId, List<Root> roots,
                          int intervalMinutes, Boolean realtime, List<String> exclude,
                          int maxRetries, long retryBackoffMs) {

    /** 省略 {@code realtime} 键时按默认开启处理(Jackson 传 null)。 */
    public AgentConfig {
        if (realtime == null) {
            realtime = Boolean.TRUE;
        }
    }

    public record Root(String path, String remoteName) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AgentConfig load(Path file) {
        try {
            return MAPPER.readValue(Files.readAllBytes(file), AgentConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取配置失败: " + file, e);
        }
    }

    public void save(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), this);
        } catch (IOException e) {
            throw new UncheckedIOException("写入配置失败: " + file, e);
        }
    }

    public void validate() {
        require(serverUrl != null && !serverUrl.isBlank(), "serverUrl 不能为空");
        require(token != null && !token.isBlank(), "token 不能为空");
        require(deviceId != null && !deviceId.isBlank(), "deviceId 不能为空");
        require(roots != null && !roots.isEmpty(), "roots 不能为空");
        for (Root root : roots) {
            require(root != null && root.path() != null && !root.path().isBlank(),
                "roots[].path 不能为空");
        }
        require(intervalMinutes > 0, "intervalMinutes 必须为正数");
        require(maxRetries > 0, "maxRetries 必须为正数");
        require(retryBackoffMs >= 0, "retryBackoffMs 不能为负");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
