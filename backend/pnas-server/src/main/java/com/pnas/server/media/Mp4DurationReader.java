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
                long timescale;
                long duration;
                if (version == 1) {
                    data.skipNBytes(16);                // creation + modification(64 位)
                    timescale = Integer.toUnsignedLong(data.readInt());
                    duration = data.readLong();
                } else {
                    data.skipNBytes(8);                 // creation + modification(32 位)
                    timescale = Integer.toUnsignedLong(data.readInt());
                    duration = Integer.toUnsignedLong(data.readInt());
                }
                if (timescale <= 0) {
                    return null;
                }
                return duration * 1000L / timescale;
            }
            if (payload < 0) {
                return null;
            }
            data.skipNBytes(payload);
        }
    }
}
