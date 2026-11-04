package com.pnas.agent.remote;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 备份运行记录客户端。 */
public final class RunClient {

    public record StartResponse(UUID id, String status) {
    }

    /** 列表视图:仅保留 status 展示所需字段,其余服务端字段忽略。 */
    public record RunView(UUID id, String mode, String status, Instant startedAt) {
    }

    public record Finish(String status, int filesSeen, int filesUploaded, int filesSkipped,
                         int filesFailed, long bytesUploaded, String error) {
    }

    private final HttpJson http;

    public RunClient(HttpJson http) {
        this.http = http;
    }

    public UUID start(String deviceId, String mode) throws IOException, InterruptedException {
        return http.post("/api/v1/backup/runs",
            Map.of("deviceId", deviceId, "mode", mode), StartResponse.class).id();
    }

    public void finish(UUID runId, Finish finish) throws IOException, InterruptedException {
        http.patch("/api/v1/backup/runs/" + runId, finish, Map.class);
    }

    /** 列出指定设备最近的运行记录(服务端按 startedAt 倒序)。 */
    public List<RunView> list(String deviceId, int limit) throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/v1/backup/runs?limit=").append(limit);
        if (deviceId != null && !deviceId.isBlank()) {
            path.append("&deviceId=").append(URLEncoder.encode(deviceId, StandardCharsets.UTF_8));
        }
        List<RunView> runs = http.get(path.toString(), new TypeReference<List<RunView>>() {
        });
        return runs == null ? List.of() : runs;
    }
}
