package com.pnas.agent.remote;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 分块上传客户端:start → putChunk* → complete。 */
public final class UploadClient {

    private final HttpJson http;

    public UploadClient(HttpJson http) {
        this.http = http;
    }

    public UUID start(UUID parentId, String name, long size, Instant sourceMtime)
            throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("destParentId", parentId.toString());
        body.put("filename", name);
        body.put("size", size);
        if (sourceMtime != null) {
            body.put("sourceMtime", sourceMtime.toString());
        }
        Map<?, ?> response = http.post("/api/v1/uploads", body, Map.class);
        return UUID.fromString(response.get("uploadId").toString());
    }

    /** 分块 PUT:按 AgentConfig 的 maxRetries/retryBackoffMs 重试 5xx/429/IO。 */
    public void putChunk(UUID uploadId, int seq, byte[] chunk) throws IOException, InterruptedException {
        http.putBytesWithRetry("/api/v1/uploads/" + uploadId + "/chunks/" + seq, chunk,
            Sha256.hex(chunk));
    }

    /** 完成上传:可安全重放(服务端按同名文件复用节点),因此同样带重试。 */
    public UUID complete(UUID uploadId) throws IOException, InterruptedException {
        Map<?, ?> response = http.postWithRetry("/api/v1/uploads/" + uploadId + "/complete",
            Map.of(), Map.class);
        return UUID.fromString(response.get("nodeId").toString());
    }
}
