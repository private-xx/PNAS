package com.pnas.server.media;

import com.pnas.server.media.domain.MediaKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaTypesTest {

    @Test
    void classifiesByExtension() {
        assertThat(MediaTypes.classify("a.jpg", null)).isEqualTo(MediaKind.IMAGE);
        assertThat(MediaTypes.classify("a.JPEG", null)).isEqualTo(MediaKind.IMAGE);
        assertThat(MediaTypes.classify("a.HEIC", null)).isEqualTo(MediaKind.IMAGE);
        assertThat(MediaTypes.classify("a.webp", null)).isEqualTo(MediaKind.IMAGE);
        assertThat(MediaTypes.classify("v.mp4", null)).isEqualTo(MediaKind.VIDEO);
        assertThat(MediaTypes.classify("v.MKV", null)).isEqualTo(MediaKind.VIDEO);
    }

    @Test
    void classifiesByMimeWhenExtensionUnknown() {
        assertThat(MediaTypes.classify("noext", "image/png")).isEqualTo(MediaKind.IMAGE);
        assertThat(MediaTypes.classify("noext", "video/mp4")).isEqualTo(MediaKind.VIDEO);
    }

    @Test
    void nonMediaIsNull() {
        assertThat(MediaTypes.classify("notes.txt", "text/plain")).isNull();
        assertThat(MediaTypes.classify("archive.zip", "application/zip")).isNull();
        assertThat(MediaTypes.classify(null, null)).isNull();
        assertThat(MediaTypes.classify("trailingdot.", null)).isNull();
    }

    @Test
    void onlyPlainJavaDecodableImagesCanBeThumbnailed() {
        assertThat(MediaTypes.canThumbnail(MediaKind.IMAGE, "a.jpg")).isTrue();
        assertThat(MediaTypes.canThumbnail(MediaKind.IMAGE, "a.PNG")).isTrue();
        assertThat(MediaTypes.canThumbnail(MediaKind.IMAGE, "a.tiff")).isTrue();
        // M3 纯 Java 路线不支持:HEIC/WEBP/视频
        assertThat(MediaTypes.canThumbnail(MediaKind.IMAGE, "a.heic")).isFalse();
        assertThat(MediaTypes.canThumbnail(MediaKind.IMAGE, "a.webp")).isFalse();
        assertThat(MediaTypes.canThumbnail(MediaKind.VIDEO, "v.mp4")).isFalse();
    }
}
