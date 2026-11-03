package com.pnas.agent.remote;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** 备份运行记录客户端。 */
public final class RunClient {

    public record StartResponse(UUID id, String status) {
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
}
