package com.pnas.agent;

import com.pnas.agent.model.FileItem;
import com.pnas.agent.model.ManifestEntry;
import com.pnas.agent.plan.Planner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    private final Planner planner = new Planner();

    private static FileItem local(long size, Instant mtime, String first, String last) {
        return new FileItem("root/a.txt", Path.of("/tmp/a.txt"), size, mtime, first, last);
    }

    private static ManifestEntry remote(long size, Instant mtime, String first, String last) {
        return new ManifestEntry("root/a.txt", UUID.randomUUID(), size, mtime, first, last,
            "manifest-sha", 1);
    }

    @Test
    void skipsWhenSizeMtimeAndBothHashesMatch() {
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        var decision = planner.decide(local(10, t, "f", "l"), remote(10, t, "f", "l"));
        assertFalse(decision.upload());
        assertEquals("unchanged", decision.reason());
    }

    @Test
    void uploadsWhenAnyDimensionDiffers() {
        Instant t = Instant.parse("2026-10-01T00:00:00Z");

        var size = planner.decide(local(11, t, "f", "l"), remote(10, t, "f", "l"));
        assertTrue(size.upload());

        var mtime = planner.decide(local(10, t.plusSeconds(1), "f", "l"), remote(10, t, "f", "l"));
        assertTrue(mtime.upload());

        var first = planner.decide(local(10, t, "x", "l"), remote(10, t, "f", "l"));
        assertTrue(first.upload());

        var last = planner.decide(local(10, t, "f", "x"), remote(10, t, "f", "l"));
        assertTrue(last.upload());
    }

    @Test
    void reportsWhyUploadIsNeeded() {
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        assertEquals("size-changed", planner.decide(local(11, t, "f", "l"), remote(10, t, "f", "l")).reason());
        assertEquals("mtime-changed", planner.decide(local(10, t.plusSeconds(1), "f", "l"), remote(10, t, "f", "l")).reason());
        assertEquals("chunk-changed", planner.decide(local(10, t, "x", "l"), remote(10, t, "f", "l")).reason());
    }

    @Test
    void uploadsWhenRemoteMissingOrMtimeUnknown() {
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        var missing = planner.decide(local(10, t, "f", "l"), null);
        assertTrue(missing.upload());
        assertEquals("remote-missing", missing.reason());

        var unknown = planner.decide(local(10, t, "f", "l"), remote(10, null, "f", "l"));
        assertTrue(unknown.upload());
        assertEquals("mtime-unknown", unknown.reason());
    }

    @Test
    void comparesMtimeAtMillisecondPrecision() {
        // 本地 mtime 常带纳秒,DB 侧截断到微秒/毫秒;仅毫秒以内的差异不算变化。
        Instant localNanos = Instant.parse("2026-10-01T00:00:00.123456789Z");
        Instant remoteTruncated = Instant.parse("2026-10-01T00:00:00.123Z");
        assertFalse(planner.decide(local(10, localNanos, "f", "l"),
            remote(10, remoteTruncated, "f", "l")).upload());

        Instant nextMilli = Instant.parse("2026-10-01T00:00:00.124Z");
        assertTrue(planner.decide(local(10, nextMilli, "f", "l"),
            remote(10, remoteTruncated, "f", "l")).upload());
    }

    @Test
    void uploadsWhenHashUnknownOnEitherSide() {
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        assertTrue(planner.decide(local(10, t, null, null), remote(10, t, "f", "l")).upload());
        assertTrue(planner.decide(local(10, t, "f", "l"), remote(10, t, null, null)).upload());
        assertFalse(planner.decide(local(0, t, null, null), remote(0, t, null, null)).upload());
    }
}
