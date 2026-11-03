package com.pnas.agent.remote;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 远端节点树客户端:列目录、确保目录、下载内容。 */
public final class NodeClient {

    public record NodeDto(UUID id, String name, String kind, long sizeBytes) {
    }

    private final HttpJson http;

    public NodeClient(HttpJson http) {
        this.http = http;
    }

    public List<NodeDto> listChildren(UUID parentId) throws IOException, InterruptedException {
        List<Map<String, Object>> raw = http.get("/api/v1/nodes?parentId=" + parentId,
            new TypeReference<List<Map<String, Object>>>() {
            });
        return raw.stream().map(m -> new NodeDto(UUID.fromString((String) m.get("id")),
            (String) m.get("name"), (String) m.get("kind"),
            m.get("sizeBytes") == null ? 0L : ((Number) m.get("sizeBytes")).longValue())).toList();
    }

    /** 从 root 逐级确保目录存在,返回末级目录 id。relDir 使用 '/' 分隔。 */
    public UUID ensureDir(UUID rootNodeId, String relDir) throws IOException, InterruptedException {
        UUID current = rootNodeId;
        for (String segment : relDir.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            String name = segment;
            UUID parent = current;
            var existing = listChildren(parent).stream()
                .filter(c -> "DIR".equals(c.kind()) && name.equals(c.name()))
                .findFirst();
            if (existing.isPresent()) {
                current = existing.get().id();
            } else {
                Map<?, ?> created = http.post("/api/v1/nodes",
                    Map.of("parentId", parent.toString(), "name", name), Map.class);
                current = UUID.fromString(created.get("id").toString());
            }
        }
        return current;
    }

    public byte[] download(UUID nodeId) throws IOException, InterruptedException {
        return http.getBytes("/api/v1/nodes/" + nodeId + "/content");
    }
}
