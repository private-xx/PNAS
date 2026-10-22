package com.pnas.server.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 纯 Java 解析 MP4/MOV 的 mvhd 时长(spec D4);不做完整容器解析,无法确定时返回 null。 */
@Component
public class Mp4DurationReader {

    private static final Logger log = LoggerFactory.getLogger(Mp4DurationReader.class);
    private static final long MAX_SKIP_BYTES = 64L * 1024 * 1024;
    private static final long MAX_DURATION_FOR_MS = Long.MAX_VALUE / 1000;

    public Long readDurationMs(InputStream in) {
        try {
            DataInputStream data = new DataInputStream(in);
            long skipped = 0;
            while (skipped < MAX_SKIP_BYTES) {
                long size = Integer.toUnsignedLong(data.readInt());
                byte[] type = new byte[4];
                data.readFully(type);
                String box = new String(type, StandardCharsets.US_ASCII);
                long header = 8;
                if (size == 1) {
                    size = data.readLong();
                    header = 16;
                } else if (size == 0) {
                    return null; // 延伸到文件末尾:无法定位后续 box
                }
                if ("moov".equals(box)) {
                    return readMvhd(data);
                }
                long payload = size - header;
                if (payload < 0) {
                    return null;
                }
                if (payload > MAX_SKIP_BYTES - skipped) {
                    return null; // 超大前置 box(如 mdat)不整段扫描
                }
                data.skipNBytes(payload);
                skipped += size;
            }
            return null;
        } catch (Exception e) {
            log.debug("MP4 时长解析失败(按未知处理): {}", e.getMessage());
            return null;
        }
    }

    private Long readMvhd(DataInputStream data) throws IOException {
        long skipped = 0;
        while (true) {
            long size = Integer.toUnsignedLong(data.readInt());
            byte[] type = new byte[4];
            data.readFully(type);
            String box = new String(type, StandardCharsets.US_ASCII);
            long header = 8;
            if (size == 1) {
                size = data.readLong();
                header = 16;
            }
            long payload = size - header;
            if ("mvhd".equals(box)) {
                int version = data.readUnsignedByte();
                data.skipNBytes(3);                     // flags
                if (version != 0 && version != 1) {
                    return null;                        // 未知版本,不做猜测
                }
                long timescale;
                long duration;
                if (version == 1) {
                    if (payload < 32) {
                        return null;                    // 长度不足,避免读到后续 box
                    }
                    data.skipNBytes(16);                // creation + modification(64 位)
                    timescale = Integer.toUnsignedLong(data.readInt());
                    duration = data.readLong();
                } else {
                    if (payload < 20) {
                        return null;                    // 长度不足,避免读到后续 box
                    }
                    data.skipNBytes(8);                 // creation + modification(32 位)
                    timescale = Integer.toUnsignedLong(data.readInt());
                    duration = Integer.toUnsignedLong(data.readInt());
                }
                if (timescale <= 0) {
                    return null;
                }
                if (duration == 0) {
                    return null;                        // 0 视为未知时长
                }
                if (duration < 0 || duration > MAX_DURATION_FOR_MS) {
                    return null;                        // 乘法溢出保护
                }
                return duration * 1000L / timescale;
            }
            if (payload < 0) {
                return null;
            }
            if (payload > MAX_SKIP_BYTES - skipped) {
                return null;                            // moov 内超大子 box 同样不整段扫描
            }
            data.skipNBytes(payload);
            skipped += payload + header;
        }
    }
}
