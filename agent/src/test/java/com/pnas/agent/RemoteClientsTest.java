package com.pnas.agent;

import com.pnas.agent.model.ManifestEntry;
import com.pnas.agent.remote.AgentException;
import com.pnas.agent.remote.HttpJson;
import com.pnas.agent.remote.ManifestClient;
import com.pnas.agent.remote.NodeClient;
import com.pnas.agent.remote.RunClient;
import com.pnas.agent.remote.UploadClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteClientsTest {

    @Test
    void clientsSpeakTheProtocol() throws Exception {
        try (StubServer stub = new StubServer()) {
            HttpJson http = new HttpJson(stub.baseUrl(), "good-token");
            NodeClient nodes = new NodeClient(http);
            UUID root = StubServer.ROOT;

            UUID dir = nodes.ensureDir(root, "Docs");
            assertEquals(dir, nodes.ensureDir(root, "Docs"), "ensureDir 必须幂等");
            List<NodeClient.NodeDto> children = nodes.listChildren(root);
            assertEquals(1, children.size(), "重复 ensureDir 不应产生重复目录");
            assertTrue(children.stream().anyMatch(n -> "Docs".equals(n.name())
                && "DIR".equals(n.kind())));
            assertEquals(dir, children.get(0).id());

            UploadClient uploads = new UploadClient(http);
            Instant mtime = Instant.parse("2026-10-03T10:00:00Z");
            byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
            UUID uploadId = uploads.start(dir, "a.txt", content.length, mtime);
            assertNotNull(uploadId);
            uploads.putChunk(uploadId, 0, content);
            UUID nodeId = uploads.complete(uploadId);
            assertEquals(1, stub.chunkPuts.get());
            assertEquals(List.of("a.txt"), stub.uploadedPaths);
            assertArrayEquals(content, nodes.download(nodeId), "下载应返回上传的原始内容");

            ManifestClient manifests = new ManifestClient(http);
            ManifestClient.Page page = manifests.page("dev-1", null, 200);
            assertEquals(root, page.rootNodeId());
            ManifestEntry entry = page.items().stream()
                .filter(e -> "Docs/a.txt".equals(e.relPath())).findFirst().orElseThrow();
            assertEquals(nodeId, entry.nodeId());
            assertEquals(content.length, entry.size());
            assertEquals(mtime, entry.sourceMtime());
            assertEquals(Sha.first(content), entry.firstChunkSha());
            assertEquals(Sha.last(content), entry.lastChunkSha());
            assertEquals(Sha.manifest(content), entry.manifestSha256());
            assertEquals(1, entry.versionNo());

            RunClient runs = new RunClient(http);
            UUID runId = runs.start("dev-1", "MANUAL");
            assertTrue(stub.startedRuns.contains(runId.toString()));
            runs.finish(runId, new RunClient.Finish("SUCCESS", 1, 1, 0, 0, content.length, null));
            assertEquals(List.of(runId.toString()), stub.finishedRuns);
        }
    }

    @Test
    void manifestWalkIncludesDirectoryPrefixesAndPaginates() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.seedFile("A/B/c.txt", "hello");
            stub.seedFile("A/d.txt", "");
            HttpJson http = new HttpJson(stub.baseUrl(), "t");
            ManifestClient manifests = new ManifestClient(http);

            ManifestClient.Page first = manifests.page("dev-1", null, 1);
            assertEquals(1, first.items().size());
            assertNotNull(first.nextCursor(), "还有下一页时必须给出 cursor");

            List<ManifestEntry> all = manifests.all("dev-1");
            assertEquals(List.of("A/B/c.txt", "A/d.txt"),
                all.stream().map(ManifestEntry::relPath).toList());
            ManifestEntry empty = all.get(1);
            assertEquals(0, empty.size());
            assertEquals(Sha.manifest(new byte[0]), empty.manifestSha256());
            assertEquals(Sha.first("hello".getBytes(StandardCharsets.UTF_8)), all.get(0).firstChunkSha());
        }
    }

    @Test
    void seededFileContentIsDownloadable() throws Exception {
        try (StubServer stub = new StubServer()) {
            UUID nodeId = stub.seedFile("Docs/a.txt", "abc");
            HttpJson http = new HttpJson(stub.baseUrl(), "t");
            assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8),
                new NodeClient(http).download(nodeId));
        }
    }

    @Test
    void manifestCursorEscapesSpecialCharactersAcrossPages() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.seedFile("A b/c&d.txt", "one");
            stub.seedFile("A b/e.txt", "two");
            HttpJson http = new HttpJson(stub.baseUrl(), "t");
            ManifestClient manifests = new ManifestClient(http);

            ManifestClient.Page first = manifests.page("dev-1", null, 1);
            assertEquals(1, first.items().size());
            assertEquals("A b/c&d.txt", first.items().get(0).relPath());
            assertNotNull(first.nextCursor(), "还有下一页时必须给出 cursor");

            List<ManifestEntry> all = manifests.all("dev-1", 1);
            assertEquals(List.of("A b/c&d.txt", "A b/e.txt"),
                all.stream().map(ManifestEntry::relPath).toList(),
                "含空格/&//的 relPath 游标必须正确编码并继续翻页");
        }
    }

    @Test
    void manifestAllReturnsEveryEntryOfTwoFileManifest() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.seedFile("a.txt", "1");
            stub.seedFile("b.txt", "2");
            HttpJson http = new HttpJson(stub.baseUrl(), "t");
            List<ManifestEntry> all = new ManifestClient(http).all("dev-1");
            assertEquals(2, all.size(), "两文件清单应完整返回 2 条");
            assertEquals(List.of("a.txt", "b.txt"),
                all.stream().map(ManifestEntry::relPath).toList());
        }
    }

    @Test
    void chunkWithWrongSha256IsRejected() throws Exception {
        try (StubServer stub = new StubServer()) {
            HttpJson http = new HttpJson(stub.baseUrl(), "t");
            UploadClient uploads = new UploadClient(http);
            byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
            UUID uploadId = uploads.start(StubServer.ROOT, "x.txt", content.length,
                Instant.parse("2026-10-03T10:00:00Z"));

            AgentException e = assertThrows(AgentException.class,
                () -> http.putBytes("/api/v1/uploads/" + uploadId + "/chunks/0",
                    content, "0".repeat(64)), "错误的 X-Sha256 必须被拒绝");
            assertEquals(400, e.status());
        }
    }

    @Test
    void chunkSequenceOutOfRangeIsRejected() throws Exception {
        try (StubServer stub = new StubServer()) {
            HttpJson http = new HttpJson(stub.baseUrl(), "t");
            UploadClient uploads = new UploadClient(http);
            byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
            UUID uploadId = uploads.start(StubServer.ROOT, "x.txt", content.length,
                Instant.parse("2026-10-03T10:00:00Z"));

            AgentException e = assertThrows(AgentException.class,
                () -> uploads.putChunk(uploadId, 1, content), "越界 seq 必须被拒绝");
            assertEquals(400, e.status());
        }
    }

    @Test
    void completeReusesSameNameFileAndBumpsVersion() throws Exception {
        try (StubServer stub = new StubServer()) {
            HttpJson http = new HttpJson(stub.baseUrl(), "t");
            UploadClient uploads = new UploadClient(http);
            NodeClient nodes = new NodeClient(http);

            byte[] first = "one".getBytes(StandardCharsets.UTF_8);
            UUID firstUpload = uploads.start(StubServer.ROOT, "same.txt", first.length, null);
            uploads.putChunk(firstUpload, 0, first);
            UUID firstNode = uploads.complete(firstUpload);

            byte[] second = "two2".getBytes(StandardCharsets.UTF_8);
            UUID secondUpload = uploads.start(StubServer.ROOT, "same.txt", second.length, null);
            uploads.putChunk(secondUpload, 0, second);
            UUID secondNode = uploads.complete(secondUpload);

            assertEquals(firstNode, secondNode, "同名文件必须复用节点而不是追加重复节点");
            assertArrayEquals(second, nodes.download(secondNode));
            List<ManifestEntry> all = new ManifestClient(http).all("dev-1");
            assertEquals(1, all.size(), "同名文件覆盖后清单应仍只有一条");
            assertEquals(2, all.get(0).versionNo(), "重写同名文件必须递增版本");
        }
    }

    @Test
    void ensureDirCreatesMultiSegmentPathIdempotentlyAndRejectsFileConflict() throws Exception {
        try (StubServer stub = new StubServer()) {
            HttpJson http = new HttpJson(stub.baseUrl(), "t");
            NodeClient nodes = new NodeClient(http);

            UUID ab = nodes.ensureDir(StubServer.ROOT, "A/B");
            assertEquals(ab, nodes.ensureDir(StubServer.ROOT, "A/B"),
                "多级 ensureDir 必须幂等");
            UUID a = nodes.listChildren(StubServer.ROOT).stream()
                .filter(n -> "A".equals(n.name())).findFirst().orElseThrow().id();
            assertEquals(1, nodes.listChildren(a).size(), "重复 ensureDir 不应产生重复目录");
            assertEquals(ab, nodes.listChildren(a).get(0).id());

            stub.seedFile("C/D", "occupied");
            AgentException e = assertThrows(AgentException.class,
                () -> nodes.ensureDir(StubServer.ROOT, "C/D"),
                "同名 FILE 占位时 ensureDir 必须失败而不是静默建目录");
            assertEquals(409, e.status());
        }
    }

    @Test
    void revokedTokenRaisesUnauthorized() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.revokedToken = "bad-token";
            HttpJson http = new HttpJson(stub.baseUrl(), "bad-token");
            AgentException e = assertThrows(AgentException.class,
                () -> new ManifestClient(http).page("dev-1", null, 10));
            assertEquals(401, e.status());
        }
    }

    @Test
    void nonSuccessStatusRaisesAgentException() throws Exception {
        try (StubServer stub = new StubServer()) {
            HttpJson http = new HttpJson(stub.baseUrl(), "t");
            AgentException e = assertThrows(AgentException.class,
                () -> http.get("/api/v1/unknown", Map.class));
            assertEquals(404, e.status());
            AgentException missing = assertThrows(AgentException.class,
                () -> http.get("/api/v1/unknown", new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                }));
            assertEquals(404, missing.status());
            assertFalse(e.getMessage().isBlank());
        }
    }
}
