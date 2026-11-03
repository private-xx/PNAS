package com.pnas.agent.restore;

import com.pnas.agent.model.ManifestEntry;
import com.pnas.agent.remote.ManifestClient;
import com.pnas.agent.remote.NodeClient;
import com.pnas.agent.remote.Sha256;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 从备份清单恢复文件:按清单中的 {@code manifestSha256} 校验下载内容,
 * 校验通过后写入目标目录。目标路径会剥离清单 relPath 的第一段(远程名)。
 */
public final class Restorer {

    private final ManifestClient manifests;
    private final NodeClient nodes;
    private final int chunkSize;

    public Restorer(ManifestClient manifests, NodeClient nodes, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须为正数: " + chunkSize);
        }
        this.manifests = manifests;
        this.nodes = nodes;
        this.chunkSize = chunkSize;
    }

    /**
     * 恢复单个文件或目录。{@code relPath} 为清单中的相对路径(含远程名首段);
     * 目录恢复取所有以 {@code relPath + "/"} 开头的条目。
     *
     * @return 恢复(含因已存在而跳过)的文件数
     */
    public int restore(String deviceId, String relPath, Path dest, boolean overwrite)
            throws IOException, InterruptedException {
        String path = requireRelative(relPath);
        List<ManifestEntry> entries = manifests.all(deviceId);
        Optional<ManifestEntry> exact = entries.stream()
            .filter(e -> path.equals(e.relPath()))
            .findFirst();
        List<ManifestEntry> targets = exact.<List<ManifestEntry>>map(List::of).orElseGet(() ->
            entries.stream().filter(e -> e.relPath().startsWith(path + "/")).toList());
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("备份中不存在: " + relPath);
        }

        Path destRoot = dest.toAbsolutePath().normalize();
        int count = 0;
        for (ManifestEntry entry : targets) {
            String relative = relativeToBackup(entry.relPath());
            if (relative.isBlank()) {
                throw new IllegalArgumentException("非法清单路径: " + entry.relPath());
            }
            Path target = destRoot.resolve(relative).normalize();
            if (!target.startsWith(destRoot)) {
                throw new IllegalArgumentException("清单路径越界: " + entry.relPath());
            }
            if (Files.exists(target) && !overwrite) {
                count++;
                continue;
            }
            byte[] content = nodes.download(entry.nodeId());
            if (!manifestSha256(content, chunkSize).equals(entry.manifestSha256())) {
                throw new IllegalStateException("哈希校验失败: " + entry.relPath());
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, content);
            count++;
        }
        return count;
    }

    /** 剥离清单 relPath 的首段(远程名);无分隔符时原样返回。 */
    static String relativeToBackup(String entryRelPath) {
        int slash = entryRelPath.indexOf('/');
        return slash < 0 ? entryRelPath : entryRelPath.substring(slash + 1);
    }

    /**
     * 服务端清单哈希规则:按 {@code chunkSize} 分块,逐块 hex-sha256,
     * 拼接全部十六进制串后再取 sha256。
     */
    static String manifestSha256(byte[] content, int chunkSize) {
        List<String> chunkHashes = new ArrayList<>();
        for (int offset = 0; offset < content.length; offset += chunkSize) {
            int end = (int) Math.min((long) content.length, (long) offset + chunkSize);
            byte[] chunk = new byte[end - offset];
            System.arraycopy(content, offset, chunk, 0, chunk.length);
            chunkHashes.add(Sha256.hex(chunk));
        }
        return Sha256.hex(String.join("", chunkHashes).getBytes(StandardCharsets.UTF_8));
    }

    private static String requireRelative(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("非法相对路径: " + relPath);
        }
        if (relPath.startsWith("/") || relPath.startsWith("\\")) {
            throw new IllegalArgumentException("非法相对路径: " + relPath);
        }
        for (String segment : relPath.split("/", -1)) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("非法相对路径: " + relPath);
            }
        }
        return relPath;
    }
}
