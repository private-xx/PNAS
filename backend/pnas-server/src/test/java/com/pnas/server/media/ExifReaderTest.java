package com.pnas.server.media;

import com.pnas.server.media.testfixtures.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

class ExifReaderTest {

    private final ExifReader reader = new ExifReader();

    private TimeZone originalZone;

    /**
     * EXIF DateTimeOriginal 是无时区墙钟,按 JVM 默认时区解释。宿主 JVM 若是 UTC,
     * 这组用例会退化成"UTC 进 UTC 出";钉死 Asia/Shanghai 才能验证本地墙钟语义。
     */
    @BeforeEach
    void pinTimezone() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    @AfterEach
    void restoreTimezone() {
        TimeZone.setDefault(originalZone);
    }

    @Test
    void readsTakenAtOrientationAndGps() throws Exception {
        LocalDateTime wallClock = LocalDateTime.parse("2026-03-01T10:20:30");
        byte[] jpeg = TestImages.jpegWithExif(wallClock, 39.9075, 116.3972, 6, 800, 600);

        var exif = reader.read(new ByteArrayInputStream(jpeg));

        // 10:20:30 +08:00 = 02:20:30Z;固定 UTC 解释会得到 10:20:30Z
        assertThat(exif.takenAt()).isEqualTo(Instant.parse("2026-03-01T02:20:30Z"));
        assertThat(DateTimeFormatter.ISO_LOCAL_DATE.format(
            exif.takenAt().atZone(ZoneId.of("Asia/Shanghai")))).isEqualTo("2026-03-01");
        assertThat(exif.orientation()).isEqualTo(6);
        assertThat(exif.gpsLat()).isCloseTo(39.9075, org.assertj.core.data.Offset.offset(0.001));
        assertThat(exif.gpsLon()).isCloseTo(116.3972, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void nearMidnightWallClockKeepsLocalDate() throws Exception {
        LocalDateTime wallClock = LocalDateTime.parse("2026-02-28T23:30:00");
        byte[] jpeg = TestImages.jpegWithExif(wallClock, 39.9075, 116.3972, 1, 800, 600);

        var exif = reader.read(new ByteArrayInputStream(jpeg));

        // 23:30 +08:00 = 15:30Z,UTC 解释 -> 当地 3 月 1 日 07:30,本地日期就变了
        assertThat(exif.takenAt()).isEqualTo(Instant.parse("2026-02-28T15:30:00Z"));
        String localDate = DateTimeFormatter.ISO_LOCAL_DATE.format(
            exif.takenAt().atZone(ZoneId.of("Asia/Shanghai")));
        assertThat(localDate).isEqualTo("2026-02-28");
    }

    @Test
    void readsDimensionsOfPlainJpegAndReturnsNullsForMissingMetadata() throws Exception {
        byte[] jpeg = TestImages.plainJpeg(320, 240);

        var exif = reader.read(new ByteArrayInputStream(jpeg));

        assertThat(exif.width()).isEqualTo(320);
        assertThat(exif.height()).isEqualTo(240);
        assertThat(exif.takenAt()).isNull();
        assertThat(exif.gpsLat()).isNull();
    }

    @Test
    void malformedInputDegradesInsteadOfThrowing() {
        var exif = reader.read(new ByteArrayInputStream("not-an-image".getBytes()));

        assertThat(exif.takenAt()).isNull();
        assertThat(exif.width()).isNull();
        assertThat(exif.orientation()).isNull();
    }
}
