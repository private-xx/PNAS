package com.pnas.server.media.job;

import com.pnas.server.files.FileVersionRepository;
import com.pnas.server.files.NodeRepository;
import com.pnas.server.files.domain.Node;
import com.pnas.server.job.domain.Job;
import com.pnas.server.job.JobHandler;
import com.pnas.server.job.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * media.reindex:按 id 顺序分批回填/刷新媒体索引,每批 200,必要时自链式入队下一批。
 *
 * <p>统一入队 media.scan(而不是直接 ingest):scan 负责入库并再入队 media.thumb,
 * 因此 pre-M3 遗留的 PENDING 资产也能在重建时补齐缩略图;单节点失败不会中断整批。</p>
 */
@Component
public class MediaReindexHandler implements JobHandler {

    private static final int BATCH = 200;
    private static final Logger log = LoggerFactory.getLogger(MediaReindexHandler.class);

    private final NodeRepository nodes;
    private final FileVersionRepository versions;
    private final ObjectProvider<JobService> jobs;

    public MediaReindexHandler(NodeRepository nodes, FileVersionRepository versions,
                               ObjectProvider<JobService> jobs) {
        this.nodes = nodes;
        this.versions = versions;
        this.jobs = jobs;
    }

    @Override
    public String type() {
        return "media.reindex";
    }

    @Override
    public Map<String, Object> run(Job job) {
        Object cursor = job.getPayload().get("cursor");
        List<Node> batch = cursor == null
            ? nodes.findTop200ByOrderByIdAsc()
            : nodes.findTop200ByIdGreaterThanOrderByIdAsc(UUID.fromString((String) cursor));

        int scanned = 0;
        int failed = 0;
        for (Node node : batch) {
            // 回收站中的节点不重建索引(仍保留既有 media_assets,读取侧再按 ACL/回收站过滤)
            if (node.getKind() != Node.Kind.FILE || node.getTrashedAt() != null) {
                continue;
            }
            // 单节点失败(异常/版本缺失等)只计数并继续,绝不中断整批;下一批仍会链式入队
            try {
                var latest = versions.findTopByNodeIdOrderByVersionNoDesc(node.getId());
                if (latest.isEmpty()) {
                    continue;
                }
                jobs.getObject().enqueue("media.scan", Map.of(
                    "nodeId", node.getId().toString(),
                    "versionId", latest.get().getId().toString()), 5, 0);
                scanned++;
            } catch (Exception e) {
                failed++;
                log.warn("重建索引跳过节点 nodeId={}: {}", node.getId(), e.getMessage(), e);
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("scanned", scanned);
        out.put("failed", failed);
        if (batch.size() == BATCH) {
            String next = batch.get(batch.size() - 1).getId().toString();
            jobs.getObject().enqueue("media.reindex", Map.of("cursor", next), 1, 0);
            out.put("next", next);
        } else {
            out.put("next", null);
        }
        return out;
    }
}
