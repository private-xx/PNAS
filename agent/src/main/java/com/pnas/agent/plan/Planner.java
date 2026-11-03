package com.pnas.agent.plan;

import com.pnas.agent.model.FileItem;
import com.pnas.agent.model.ManifestEntry;

import java.util.Objects;

/**
 * 增量决策:只有大小、mtime 与首末块哈希全部一致才跳过上传。
 * mtime 按 {@link java.time.Instant#toEpochMilli()} 比较,避免 PostgreSQL 微秒截断造成的反复上传。
 */
public final class Planner {

    public record Decision(boolean upload, String reason) {
    }

    public Decision decide(FileItem local, ManifestEntry remote) {
        if (remote == null) {
            return new Decision(true, "remote-missing");
        }
        if (local.size() != remote.size()) {
            return new Decision(true, "size-changed");
        }
        if (remote.sourceMtime() == null || local.mtime() == null) {
            return new Decision(true, "mtime-unknown");
        }
        if (local.mtime().toEpochMilli() != remote.sourceMtime().toEpochMilli()) {
            return new Decision(true, "mtime-changed");
        }
        if (!Objects.equals(local.firstChunkSha(), remote.firstChunkSha())
            || !Objects.equals(local.lastChunkSha(), remote.lastChunkSha())) {
            return new Decision(true, "chunk-changed");
        }
        return new Decision(false, "unchanged");
    }
}
