package com.pnas.agent.model;

import java.time.Instant;
import java.util.UUID;

/** 备份清单中的一条远端记录。 */
public record ManifestEntry(String relPath, UUID nodeId, long size, Instant sourceMtime,
                            String firstChunkSha, String lastChunkSha, String manifestSha256,
                            int versionNo) {
}
