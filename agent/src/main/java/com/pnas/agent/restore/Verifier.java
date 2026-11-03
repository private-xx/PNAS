package com.pnas.agent.restore;

import com.pnas.agent.model.ManifestEntry;
import com.pnas.agent.remote.AgentException;
import com.pnas.agent.remote.ManifestClient;
import com.pnas.agent.remote.NodeClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 抽样/全量校验远端备份:逐个下载清单文件并比对 {@code manifestSha256}。
 * 一次只保留一个文件内容,内存占用与备份规模无关。
 */
public final class Verifier {

    public record VerifyResult(int checked, int passed, List<String> failed) {

        public VerifyResult {
            failed = List.copyOf(failed);
        }
    }

    private final ManifestClient manifests;
    private final NodeClient nodes;
    private final int chunkSize;

    public Verifier(ManifestClient manifests, NodeClient nodes, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须为正数: " + chunkSize);
        }
        this.manifests = manifests;
        this.nodes = nodes;
        this.chunkSize = chunkSize;
    }

    /**
     * @param limit 最多检查的文件数;小于等于 0 表示全量
     */
    public VerifyResult verify(String deviceId, int limit) throws IOException, InterruptedException {
        List<ManifestEntry> entries = manifests.all(deviceId);
        List<String> failed = new ArrayList<>();
        int checked = 0;
        int passed = 0;
        for (ManifestEntry entry : entries) {
            if (limit > 0 && checked >= limit) {
                break;
            }
            checked++;
            if (matches(entry)) {
                passed++;
            } else {
                failed.add(entry.relPath());
            }
        }
        return new VerifyResult(checked, passed, failed);
    }

    /** 兼容计划中的命名:等价于 {@link #verify(String, int)}。 */
    public VerifyResult verifyAll(String deviceId, int sampleLimit)
            throws IOException, InterruptedException {
        return verify(deviceId, sampleLimit);
    }

    private boolean matches(ManifestEntry entry) throws IOException, InterruptedException {
        byte[] content;
        try {
            content = nodes.download(entry.nodeId());
        } catch (AgentException e) {
            // 鉴权失败是整体性错误,应中止并让 CLI 以退出码 2 结束。
            if (e.status() == 401 || e.status() == 403) {
                throw e;
            }
            return false;
        }
        return Restorer.manifestSha256(content, chunkSize).equals(entry.manifestSha256());
    }
}
