package com.pnas.agent.run;

import com.pnas.agent.config.AgentConfig;
import com.pnas.agent.model.FileItem;
import com.pnas.agent.model.ManifestEntry;
import com.pnas.agent.plan.Planner;
import com.pnas.agent.remote.ManifestClient;
import com.pnas.agent.remote.NodeClient;
import com.pnas.agent.remote.RunClient;
import com.pnas.agent.remote.UploadClient;
import com.pnas.agent.scan.Scanner;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一轮备份的执行器:单飞、增量决策、分块上传与运行记录。
 * 单飞期间并发调用直接返回全零统计({@code uploaded/skipped/failed/bytes} 均为 0)。
 */
public final class Runner {

    /** 一轮备份的统计。 */
    public record Stats(int seen, int uploaded, int skipped, int failed, long bytes) {
    }

    private static final int MANIFEST_PAGE_SIZE = 200;

    private final AgentConfig config;
    private final Scanner scanner;
    private final Planner planner;
    private final ManifestClient manifests;
    private final UploadClient uploads;
    private final NodeClient nodes;
    private final RunClient runs;
    private final int chunkSize;
    private final AtomicBoolean busy = new AtomicBoolean();

    public Runner(AgentConfig config, Scanner scanner, Planner planner, ManifestClient manifests,
                  UploadClient uploads, NodeClient nodes, RunClient runs, int chunkSize) {
        this.config = config;
        this.scanner = scanner;
        this.planner = planner;
        this.manifests = manifests;
        this.uploads = uploads;
        this.nodes = nodes;
        this.runs = runs;
        this.chunkSize = chunkSize;
    }

    /**
     * 执行一轮备份。已被其他线程占用时返回全零统计(本轮未做任何事)。
     *
     * @throws Exception 启动运行记录、拉取清单等致命错误;此时已尝试结束为 FAILED
     */
    public Stats run(RunMode mode) throws Exception {
        if (!busy.compareAndSet(false, true)) {
            return new Stats(0, 0, 0, 0, 0);
        }
        UUID runId = null;
        int seen = 0;
        int uploaded = 0;
        int skipped = 0;
        int failed = 0;
        long bytes = 0;
        boolean finished = false;
        try {
            runId = runs.start(config.deviceId(), mode.name());

            ManifestClient.Page firstPage = manifests.page(config.deviceId(), null, MANIFEST_PAGE_SIZE);
            UUID rootNodeId = firstPage.rootNodeId();
            Map<String, ManifestEntry> remote = new HashMap<>();
            for (ManifestEntry entry : manifests.all(config.deviceId(), MANIFEST_PAGE_SIZE)) {
                remote.put(entry.relPath(), entry);
            }

            for (AgentConfig.Root root : config.roots()) {
                List<FileItem> items = scanner.scan(Path.of(root.path()), root.remoteName(),
                    config.exclude(), chunkSize);
                for (FileItem item : items) {
                    seen++;
                    ManifestEntry known = remote.get(item.relPath());
                    if (!planner.decide(item, known).upload()) {
                        skipped++;
                        continue;
                    }
                    try {
                        UUID parentId = parentId(item.relPath(), rootNodeId);
                        UUID uploadId = uploads.start(parentId,
                            item.path().getFileName().toString(), item.size(), item.mtime());
                        try (InputStream in = Files.newInputStream(item.path())) {
                            byte[] buffer = new byte[chunkSize];
                            int seq = 0;
                            while (true) {
                                int n = in.read(buffer);
                                if (n < 0) {
                                    break;
                                }
                                // 短读补齐:块必须填满,否则末块会被提前切小、清单首末块哈希对不上。
                                while (n < buffer.length) {
                                    int r = in.read(buffer, n, buffer.length - n);
                                    if (r < 0) {
                                        break;
                                    }
                                    n += r;
                                }
                                if (n == 0) {
                                    break;
                                }
                                uploads.putChunk(uploadId, seq++, Arrays.copyOf(buffer, n));
                            }
                        }
                        uploads.complete(uploadId);
                        uploaded++;
                        bytes += item.size();
                    } catch (Exception e) {
                        failed++;
                        System.err.println("上传失败 " + item.relPath() + ": " + e.getMessage());
                    }
                }
            }

            String status = failed > 0 ? "PARTIAL" : "SUCCESS";
            String error = failed > 0 ? failed + " 个文件失败" : null;
            runs.finish(runId, new RunClient.Finish(status, seen, uploaded, skipped, failed, bytes,
                error));
            finished = true;
            return new Stats(seen, uploaded, skipped, failed, bytes);
        } catch (Exception e) {
            if (runId != null && !finished) {
                try {
                    runs.finish(runId, new RunClient.Finish("FAILED", seen, uploaded, skipped, failed,
                        bytes, e.getMessage()));
                } catch (Exception suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw e;
        } finally {
            busy.set(false);
        }
    }

    /** relPath 的父目录:根目录下的文件直接用 rootNodeId,否则逐级 ensureDir。 */
    private UUID parentId(String relPath, UUID rootNodeId) throws Exception {
        int slash = relPath.lastIndexOf('/');
        if (slash < 0) {
            return rootNodeId;
        }
        return nodes.ensureDir(rootNodeId, relPath.substring(0, slash));
    }
}
