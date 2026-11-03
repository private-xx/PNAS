package com.pnas.agent.model;

import java.nio.file.Path;
import java.time.Instant;

/** 本地扫描得到的一个文件条目。 */
public record FileItem(String relPath, Path path, long size, Instant mtime,
                       String firstChunkSha, String lastChunkSha) {
}
