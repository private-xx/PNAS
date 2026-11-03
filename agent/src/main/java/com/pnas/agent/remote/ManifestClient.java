package com.pnas.agent.remote;

import com.pnas.agent.model.ManifestEntry;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 备份清单分页客户端。 */
public final class ManifestClient {

    public record Page(UUID rootNodeId, List<ManifestEntry> items, String nextCursor) {
    }

    private static final int DEFAULT_PAGE_SIZE = 200;

    /** 防御服务端游标异常导致的无界翻页。 */
    private static final int MAX_PAGES = 10_000;

    private final HttpJson http;

    public ManifestClient(HttpJson http) {
        this.http = http;
    }

    public Page page(String deviceId, String cursor, int limit)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/v1/backup/manifest?deviceId=")
            .append(encode(deviceId)).append("&limit=").append(limit);
        if (cursor != null) {
            path.append("&cursor=").append(encode(cursor));
        }
        return http.get(path.toString(), Page.class);
    }

    /** 自动翻页拉取全部清单。 */
    public List<ManifestEntry> all(String deviceId) throws IOException, InterruptedException {
        return all(deviceId, DEFAULT_PAGE_SIZE);
    }

    /**
     * 自动翻页拉取全部清单。整轮翻页是一个重试单元:遇到 IOException 或 5xx/429
     * 时按配置从第一页重新拉取(最多 maxRetries 次),避免半份清单导致误判上传。
     * 对服务端返回的异常游标做终止守卫:缺 items、空页仍给游标、游标重复/不前进、
     * 翻页数超限时抛 {@link AgentException}(status=-1,不重试)。
     */
    public List<ManifestEntry> all(String deviceId, int pageSize)
            throws IOException, InterruptedException {
        return http.executeWithRetry(() -> walk(deviceId, pageSize));
    }

    private List<ManifestEntry> walk(String deviceId, int pageSize)
            throws IOException, InterruptedException {
        List<ManifestEntry> out = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            if (++pages > MAX_PAGES) {
                throw new AgentException(-1, "清单翻页超过 " + MAX_PAGES + " 页,疑似游标异常");
            }
            Page page = page(deviceId, cursor, pageSize);
            if (page == null || page.items() == null) {
                throw new AgentException(-1, "清单页缺少 items 字段");
            }
            String next = page.nextCursor();
            if (next != null) {
                if (page.items().isEmpty()) {
                    throw new AgentException(-1, "空页仍返回游标,翻页未前进: " + next);
                }
                if (!seenCursors.add(next)) {
                    throw new AgentException(-1, "游标重复: " + next);
                }
                if (cursor != null && next.compareTo(cursor) <= 0) {
                    throw new AgentException(-1, "游标未前进: " + cursor + " -> " + next);
                }
            }
            out.addAll(page.items());
            if (next == null) {
                return out;
            }
            cursor = next;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
