package com.pnas.server.media;

import com.pnas.server.media.testfixtures.TestImages;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class ExifReaderTest {

    private final ExifReader reader = new ExifReader();

    @Test
    void readsTakenAtOrientationAndGps() throws Exception {
        LocalDateTime wallClock = LocalDateTime.parse("2026-03-01T10:20:30");
        Instant expected = wallClock.atZone(ZoneId.systemDefault()).toInstant();
        byte[] jpeg = TestImages.jpegWithExif(wallClock, 39.9075, 116.3972, 6, 800, 600);

        var exif = reader.read(new ByteArrayInputStream(jpeg));

        assertThat(exif.takenAt()).isEqualTo(expected);
        assertThat(exif.orientation()).isEqualTo(6);
        assertThat(exif.gpsLat()).isCloseTo(39.9075, org.assertj.core.data.Offset.offset(0.001));
        assertThat(exif.gpsLon()).isCloseTo(116.3972, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void nearMidnightWallClockKeepsLocalDate() throws Exception {
        LocalDateTime wallClock = LocalDateTime.parse("2026-02-28T23:30:00");
        byte[] jpeg = TestImages.jpegWithExif(wallClock, 39.9075, 116.3972, 1, 800, 600);

        var exif = reader.read(new ByteArrayInputStream(jpeg));

        assertThat(exif.takenAt()).isNotNull();
        String localDate = DateTimeFormatter.ISO_LOCAL_DATE.format(
            exif.takenAt().atZone(ZoneId.systemDefault()));
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
