package com.pnas.agent;

import com.pnas.agent.model.ManifestEntry;
import com.pnas.agent.remote.AgentException;
import com.pnas.agent.remote.HttpJson;
import com.pnas.agent.remote.ManifestClient;
import com.pnas.agent.remote.UploadClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 重试策略:PUT/complete 与整轮清单拉取。 */
class RetryTest {

    private static HttpJson http(StubServer stub, int maxRetries) {
        return new HttpJson(stub.baseUrl(), "t").withRetry(maxRetries, 1);
    }

    @Test
    void putChunkRetriesTransient500ThenSucceeds() throws Exception {
        try (StubServer stub = new StubServer()) {
            UploadClient uploads = new UploadClient(http(stub, 3));
            byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
            UUID uploadId = uploads.start(StubServer.ROOT, "retry.txt", content.length, null);

            stub.failNextChunkPuts(2);
            uploads.putChunk(uploadId, 0, content);

            assertEquals(3, stub.chunkPutAttempts.get(), "两次 500 后第三次应成功");
            assertEquals(1, stub.chunkPuts.get(), "只有成功的那次写入计入");
        }
    }

    @Test
    void putChunkGivesUpAfterMaxRetries() throws Exception {
        try (StubServer stub = new StubServer()) {
            UploadClient uploads = new UploadClient(http(stub, 2));
            byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
            UUID uploadId = uploads.start(StubServer.ROOT, "retry.txt", content.length, null);

            stub.failNextChunkPuts(10);
            AgentException e = assertThrows(AgentException.class,
                () -> uploads.putChunk(uploadId, 0, content));
            assertEquals(500, e.status());
            assertEquals(3, stub.chunkPutAttempts.get(), "maxRetries=2 时共尝试 3 次");
        }
    }

    @Test
    void putChunkDoesNotRetry400() throws Exception {
        try (StubServer stub = new StubServer()) {
            UploadClient uploads = new UploadClient(http(stub, 3));
            byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
            UUID uploadId = uploads.start(StubServer.ROOT, "x.txt", content.length, null);

            AgentException e = assertThrows(AgentException.class,
                () -> uploads.putChunk(uploadId, 1, content), "越界 seq 的 400 不应重试");
            assertEquals(400, e.status());
            assertEquals(1, stub.chunkPutAttempts.get(), "4xx 只允许一次尝试");
        }
    }

    @Test
    void manifestAllRetriesWholePaginationOn500() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.seedFile("a.txt", "1");
            stub.seedFile("b.txt", "2");
            stub.failNextManifestGets(1);

            List<ManifestEntry> all = new ManifestClient(http(stub, 3)).all("dev-1", 1);

            assertEquals(List.of("a.txt", "b.txt"),
                all.stream().map(ManifestEntry::relPath).toList());
            assertEquals(3, stub.manifestGetAttempts.get(),
                "首跳 500 后整轮翻页应从第一页重新拉取");
        }
    }
}
