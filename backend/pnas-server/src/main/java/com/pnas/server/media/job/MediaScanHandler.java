package com.pnas.server.media.job;

import com.pnas.server.job.domain.Job;
import com.pnas.server.job.JobHandler;
import com.pnas.server.job.JobService;
import com.pnas.server.media.MediaIngestService;
import com.pnas.server.media.domain.MediaKind;
import com.pnas.server.media.domain.ThumbStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** media.scan:解析元数据入库,并对可生成缩略图的媒体入队 media.thumb。 */
@Component
public class MediaScanHandler implements JobHandler {

    private static final int[] THUMB_SIZES = {256, 512};

    private final MediaIngestService ingest;
    private final ObjectProvider<JobService> jobs;

    public MediaScanHandler(MediaIngestService ingest, ObjectProvider<JobService> jobs) {
        this.ingest = ingest;
        this.jobs = jobs;
    }

    @Override
    public String type() {
        return "media.scan";
    }

    @Override
    public Map<String, Object> run(Job job) {
        UUID nodeId = UUID.fromString((String) job.getPayload().get("nodeId"));
        UUID versionId = UUID.fromString((String) job.getPayload().get("versionId"));

        var result = ingest.ingest(nodeId, versionId);
        if (result.isEmpty()) {
            return Map.of("skipped", true);
        }
        var r = result.get();
        if (r.kind() == MediaKind.IMAGE && r.thumbStatus() == ThumbStatus.PENDING) {
            for (int size : THUMB_SIZES) {
                jobs.getObject().enqueue("media.thumb", Map.of(
                    "nodeId", nodeId.toString(),
                    "versionId", versionId.toString(),
                    "size", size), 5, 0);
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("assetId", r.assetId().toString());
        out.put("kind", r.kind().name());
        out.put("thumbStatus", r.thumbStatus().name());
        return out;
    }
}
