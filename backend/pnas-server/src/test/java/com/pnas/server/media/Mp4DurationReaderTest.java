package com.pnas.server.media;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class Mp4DurationReaderTest {

    private final Mp4DurationReader reader = new Mp4DurationReader();

    /** 构造 ftyp + moov/mvhd(version 0)最小 MP4。 */
    private byte[] mp4(int timescale, int duration) {
        ByteBuffer mvhd = ByteBuffer.allocate(100).order(ByteOrder.BIG_ENDIAN);
        mvhd.putInt(100);                       // box size
        mvhd.put("mvhd".getBytes());
        mvhd.putInt(0);                         // version 0 + flags
        mvhd.putInt(0);                         // creation
        mvhd.putInt(0);                         // modification
        mvhd.putInt(timescale);
        mvhd.putInt(duration);
        while (mvhd.position() < 100) mvhd.put((byte) 0);

        ByteBuffer moov = ByteBuffer.allocate(108).order(ByteOrder.BIG_ENDIAN);
        moov.putInt(108);                       // moov size = 8 + 100
        moov.put("moov".getBytes());
        moov.put(mvhd.array());

        ByteBuffer ftyp = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
        ftyp.putInt(24).put("ftyp".getBytes()).put("isom".getBytes()).putInt(0).put("isommp42".getBytes());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ftyp.array());
        out.writeBytes(moov.array());
        return out.toByteArray();
    }

    @Test
    void readsDurationFromMvhd() {
        // timescale=600, duration=1200 → 1200*1000/600 = 2000 ms
        Long ms = reader.readDurationMs(new ByteArrayInputStream(mp4(600, 1200)));
        assertThat(ms).isEqualTo(2000L);
    }

    @Test
    void returnsNullForNonMp4Input() {
        assertThat(reader.readDurationMs(new ByteArrayInputStream("nope".getBytes()))).isNull();
    }
}
