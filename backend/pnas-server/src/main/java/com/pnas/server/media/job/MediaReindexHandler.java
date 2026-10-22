package com.pnas.server.media.job;

import com.pnas.server.files.FileVersionRepository;
import com.pnas.server.files.NodeRepository;
import com.pnas.server.files.domain.Node;
import com.pnas.server.job.domain.Job;
import com.pnas.server.job.JobHandler;
import com.pnas.server.job.JobService;
import com.pnas.server.media.MediaIngestService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** media.reindex:按 id 顺序分批回填/刷新媒体索引,每批 200,必要时自链式入队下一批。 */
@Component
public class MediaReindexHandler implements JobHandler {

    private static final int BATCH = 200;

    private final NodeRepository nodes;
    private final FileVersionRepository versions;
    private final MediaIngestService ingest;
    private final ObjectProvider<JobService> jobs;

    public MediaReindexHandler(NodeRepository nodes, FileVersionRepository versions,
                               MediaIngestService ingest, ObjectProvider<JobService> jobs) {
        this.nodes = nodes;
        this.versions = versions;
        this.ingest = ingest;
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
        for (Node node : batch) {
            if (node.getKind() != Node.Kind.FILE) {
                continue;
            }
            var latest = versions.findTopByNodeIdOrderByVersionNoDesc(node.getId());
            if (latest.isPresent()) {
                ingest.ingest(node.getId(), latest.get().getId());
                scanned++;
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("scanned", scanned);
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
