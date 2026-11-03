package com.pnas.agent;

import com.pnas.agent.remote.Sha256;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试用内存服务:节点树/清单/上传会话/运行记录。 */
public final class StubServer implements AutoCloseable {

    /** 固定根节点,使第二轮清单可跳过。 */
    public static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** 与服务端一致的 4 MiB 分块上限。 */
    private static final int CHUNK_SIZE = 4 * 1024 * 1024;

    public final HttpServer server;
    public final List<String> uploadedPaths = Collections.synchronizedList(new ArrayList<>());
    public final List<String> startedRuns = Collections.synchronizedList(new ArrayList<>());
    public final List<String> finishedRuns = Collections.synchronizedList(new ArrayList<>());
    public final AtomicInteger chunkPuts = new AtomicInteger();

    private final Map<UUID, Dir> dirs = new HashMap<>();
    private final Map<UUID, List<FileDto>> files = new HashMap<>();
    private final Map<UUID, Upload> uploads = new HashMap<>();

    /** 非空时携带该 token 的请求一律 401。 */
    public volatile String revokedToken;

    record Dir(UUID id, UUID parent, String name) {
    }

    record FileDto(UUID id, String name, long size, String firstSha, String lastSha,
                   String sourceMtime, String manifestSha, int version, byte[] content) {
    }

    record Upload(UUID id, UUID parent, String name, long size, String sourceMtime,
                  ByteArrayOutputStream bytes) {
    }

    public StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (RuntimeException e) {
            if (ex.getResponseCode() < 0) {
                send(ex, 500, Json.write(Map.of("error", String.valueOf(e.getMessage()))));
            }
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")
            || (revokedToken != null && auth.equals("Bearer " + revokedToken))) {
            send(ex, 401, "{\"code\":\"unauthorized\"}");
            return;
        }
        String query = ex.getRequestURI().getRawQuery();
        if (path.equals("/api/v1/nodes") && method.equals("GET")) {
            listNodes(ex, query);
        } else if (path.equals("/api/v1/nodes") && method.equals("POST")) {
            createNode(ex);
        } else if (path.equals("/api/v1/backup/manifest") && method.equals("GET")) {
            sendManifest(ex, query);
        } else if (path.equals("/api/v1/backup/runs") && method.equals("POST")) {
            startRun(ex);
        } else if (path.startsWith("/api/v1/backup/runs/") && method.equals("PATCH")) {
            finishedRuns.add(path.substring(path.lastIndexOf('/') + 1));
            send(ex, 200, "{}");
        } else if (path.equals("/api/v1/uploads") && method.equals("POST")) {
            startUpload(ex);
        } else if (path.matches("/api/v1/uploads/[^/]+/chunks/\\d+") && method.equals("PUT")) {
            putChunk(ex, path);
        } else if (path.matches("/api/v1/uploads/[^/]+/complete") && method.equals("POST")) {
            completeUpload(ex, path);
        } else if (path.matches("/api/v1/nodes/[^/]+/content") && method.equals("GET")) {
            downloadNode(ex, path);
        } else {
            send(ex, 404, "{}");
        }
    }

    private void listNodes(HttpExchange ex, String query) throws IOException {
        String rawParent = param(query, "parentId");
        if (rawParent.isEmpty()) {
            send(ex, 400, "{\"error\":\"parentId required\"}");
            return;
        }
        UUID parent = UUID.fromString(rawParent);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Dir d : dirs.values()) {
            if (Objects.equals(d.parent(), parent)) {
                out.add(Map.of("id", d.id().toString(), "name", d.name(), "kind", "DIR"));
            }
        }
        for (FileDto f : files.getOrDefault(parent, List.of())) {
            out.add(Map.of("id", f.id().toString(), "name", f.name(), "kind", "FILE",
                "sizeBytes", f.size()));
        }
        out.sort(Comparator.comparing(m -> (String) m.get("name")));
        send(ex, 200, Json.write(out));
    }

    private void createNode(HttpExchange ex) throws IOException {
        var body = Json.read(ex.getRequestBody().readAllBytes());
        UUID parent = UUID.fromString((String) body.get("parentId"));
        String name = (String) body.get("name");
        // 真实 FilesService:同目录下同名节点(DIR 或 FILE)一律 409。
        if (nodeExists(parent, name)) {
            send(ex, 409, Json.write(Map.of("code", "NAME_CONFLICT",
                "message", "同目录下已存在同名节点: " + name)));
            return;
        }
        UUID id = UUID.randomUUID();
        dirs.put(id, new Dir(id, parent, name));
        send(ex, 200, Json.write(Map.of("id", id.toString(), "name", name, "kind", "DIR")));
    }

    private void sendManifest(HttpExchange ex, String query) throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        for (var entry : files.entrySet()) {
            for (FileDto f : entry.getValue()) {
                Map<String, Object> m = new HashMap<>();
                m.put("relPath", relPath(entry.getKey(), f.name()));
                m.put("nodeId", f.id().toString());
                m.put("size", f.size());
                m.put("sourceMtime", f.sourceMtime());
                m.put("firstChunkSha", f.firstSha());
                m.put("lastChunkSha", f.lastSha());
                m.put("manifestSha256", f.manifestSha());
                m.put("versionNo", f.version());
                items.add(m);
            }
        }
        items.sort(Comparator.comparing(m -> (String) m.get("relPath")));

        String cursor = param(query, "cursor");
        int limit = Math.max(1, Math.min(200, parseInt(param(query, "limit"), 200)));
        List<Map<String, Object>> filtered = items.stream()
            .filter(m -> cursor.isEmpty()
                || ((String) m.get("relPath")).compareTo(cursor) > 0)
            .toList();
        List<Map<String, Object>> pageItems = filtered.subList(0,
            Math.min(filtered.size(), limit));
        String nextCursor = filtered.size() > limit
            ? (String) pageItems.get(pageItems.size() - 1).get("relPath")
            : null;

        Map<String, Object> page = new HashMap<>();
        page.put("rootNodeId", ROOT.toString());
        page.put("items", pageItems);
        page.put("nextCursor", nextCursor);
        send(ex, 200, Json.write(page));
    }

    private void startRun(HttpExchange ex) throws IOException {
        Json.read(ex.getRequestBody().readAllBytes());
        UUID id = UUID.randomUUID();
        startedRuns.add(id.toString());
        send(ex, 200, Json.write(Map.of("id", id.toString(), "status", "RUNNING")));
    }

    private void startUpload(HttpExchange ex) throws IOException {
        var body = Json.read(ex.getRequestBody().readAllBytes());
        UUID id = UUID.randomUUID();
        uploads.put(id, new Upload(id, UUID.fromString((String) body.get("destParentId")),
            (String) body.get("filename"), ((Number) body.get("size")).longValue(),
            (String) body.get("sourceMtime"), new ByteArrayOutputStream()));
        send(ex, 200, Json.write(Map.of("uploadId", id.toString())));
    }

    private void putChunk(HttpExchange ex, String path) throws IOException {
        UUID uploadId = UUID.fromString(path.split("/")[4]);
        Upload upload = uploads.get(uploadId);
        if (upload == null) {
            send(ex, 404, "{}");
            return;
        }
        byte[] body = ex.getRequestBody().readAllBytes();
        int seq = parseInt(path.split("/")[6], -1);
        String sha = ex.getRequestHeaders().getFirst("X-Sha256");
        int chunkCount = chunkCount(upload.size());
        if (seq < 0 || seq >= chunkCount) {
            send(ex, 400, Json.write(Map.of("code", "CHUNK_INVALID",
                "message", "分块序号越界: " + seq + "(共 " + chunkCount + " 块)")));
            return;
        }
        if (body.length > CHUNK_SIZE) {
            send(ex, 400, Json.write(Map.of("code", "CHUNK_INVALID",
                "message", "分块超过上限 " + CHUNK_SIZE + " 字节: " + body.length)));
            return;
        }
        // 真实服务端只接受与正文匹配的小写 hex SHA-256。
        if (sha == null || !sha.equals(Sha256.hex(body))) {
            send(ex, 400, Json.write(Map.of("code", "CHUNK_INVALID",
                "message", "分块 sha256 不匹配")));
            return;
        }
        upload.bytes().writeBytes(body);
        chunkPuts.incrementAndGet();
        send(ex, 204, "");
    }

    private void completeUpload(HttpExchange ex, String path) throws IOException {
        UUID uploadId = UUID.fromString(path.split("/")[4]);
        Upload upload = uploads.get(uploadId);
        if (upload == null) {
            send(ex, 404, "{}");
            return;
        }
        // 同名目录不能当作文件写入(真实 FilesService 409)。
        if (findDir(upload.parent(), upload.name()).isPresent()) {
            send(ex, 409, Json.write(Map.of("code", "NAME_CONFLICT",
                "message", "同名目录已存在,不能作为文件写入: " + upload.name())));
            return;
        }
        byte[] content = upload.bytes().toByteArray();
        List<FileDto> siblings = files.computeIfAbsent(upload.parent(), k -> new ArrayList<>());
        FileDto existing = siblings.stream()
            .filter(f -> f.name().equals(upload.name())).findFirst().orElse(null);
        // 真实服务端:同目录同名 FILE → 复用节点并递增版本,而不是追加重复节点。
        UUID nodeId = existing == null ? UUID.randomUUID() : existing.id();
        int version = existing == null ? 1 : existing.version() + 1;
        FileDto updated = new FileDto(nodeId, upload.name(), upload.size(), Sha.first(content),
            Sha.last(content), upload.sourceMtime(), Sha.manifest(content), version, content);
        if (existing == null) {
            siblings.add(updated);
        } else {
            siblings.set(siblings.indexOf(existing), updated);
        }
        uploadedPaths.add(upload.name());
        send(ex, 200, Json.write(Map.of("nodeId", nodeId.toString(), "versionNo", version)));
    }

    private void downloadNode(HttpExchange ex, String path) throws IOException {
        UUID nodeId = UUID.fromString(path.split("/")[4]);
        FileDto file = files.values().stream().flatMap(List::stream)
            .filter(f -> f.id().equals(nodeId)).findFirst().orElse(null);
        if (file == null) {
            send(ex, 404, "{}");
            return;
        }
        sendRaw(ex, 200, file.content());
    }

    /** 由父目录反推 relPath(相对 ROOT)。 */
    private String relPath(UUID parent, String fileName) {
        ArrayDeque<String> parts = new ArrayDeque<>();
        UUID cur = parent;
        int guard = 0;
        while (cur != null && !cur.equals(ROOT) && guard++ < 1000) {
            Dir dir = dirs.get(cur);
            if (dir == null) {
                break;
            }
            parts.addFirst(dir.name());
            cur = dir.parent();
        }
        parts.addLast(fileName);
        return String.join("/", parts);
    }

    /** 测试种子:创建目录链并写入一个文件(首末块/清单哈希按服务端规则)。 */
    public UUID seedFile(String relPath, String content) {
        String[] parts = relPath.split("/");
        UUID parent = ROOT;
        for (int i = 0; i < parts.length - 1; i++) {
            UUID cur = parent;
            String name = parts[i];
            parent = findDir(cur, name).orElseGet(() -> {
                UUID id = UUID.randomUUID();
                dirs.put(id, new Dir(id, cur, name));
                return id;
            });
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        UUID nodeId = UUID.randomUUID();
        files.computeIfAbsent(parent, k -> new ArrayList<>()).add(new FileDto(nodeId,
            parts[parts.length - 1], bytes.length, Sha.first(bytes), Sha.last(bytes),
            null, Sha.manifest(bytes), 1, bytes));
        return nodeId;
    }

    private java.util.Optional<UUID> findDir(UUID parent, String name) {
        return dirs.values().stream()
            .filter(d -> Objects.equals(d.parent(), parent) && d.name().equals(name))
            .map(Dir::id).findFirst();
    }

    private boolean nodeExists(UUID parent, String name) {
        return findDir(parent, name).isPresent()
            || files.getOrDefault(parent, List.of()).stream()
                .anyMatch(f -> f.name().equals(name));
    }

    private static int chunkCount(long size) {
        if (size <= 0) {
            return 0;
        }
        return (int) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String param(String query, String name) {
        if (query == null) {
            return "";
        }
        for (String kv : query.split("&")) {
            String[] p = kv.split("=", 2);
            if (decode(p[0]).equals(name)) {
                return p.length > 1 ? decode(p[1]) : "";
            }
        }
        return "";
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void sendRaw(HttpExchange ex, int status, byte[] bytes) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
