package com.pnas.agent.remote;

import com.pnas.agent.model.ManifestEntry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 备份清单分页客户端。 */
public final class ManifestClient {

    public record Page(UUID rootNodeId, List<ManifestEntry> items, String nextCursor) {
    }

    private static final int DEFAULT_PAGE_SIZE = 200;

    private final HttpJson http;

    public ManifestClient(HttpJson http) {
        this.http = http;
    }

    public Page page(String deviceId, String cursor, int limit)
            throws IOException, InterruptedException {
        String path = "/api/v1/backup/manifest?deviceId=" + deviceId + "&limit=" + limit
            + (cursor == null ? "" : "&cursor=" + cursor);
        return http.get(path, Page.class);
    }

    /** 自动翻页拉取全部清单。 */
    public List<ManifestEntry> all(String deviceId) throws IOException, InterruptedException {
        return all(deviceId, DEFAULT_PAGE_SIZE);
    }

    public List<ManifestEntry> all(String deviceId, int pageSize)
            throws IOException, InterruptedException {
        List<ManifestEntry> out = new ArrayList<>();
        String cursor = null;
        do {
            Page page = page(deviceId, cursor, pageSize);
            out.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);
        return out;
    }
}
