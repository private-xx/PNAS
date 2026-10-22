package com.pnas.server.media;

import com.pnas.server.blobstore.DerivedStore;
import com.pnas.server.common.PnasProperties;
import com.pnas.server.media.testfixtures.TestImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThumbnailServiceTest {

    @TempDir Path dataDir;

    private ThumbnailService service() throws Exception {
        var props = new PnasProperties(dataDir, new PnasProperties.Security(false),
            new PnasProperties.Upload(4 * 1024 * 1024), new PnasProperties.Bootstrap("admin", "x"));
        return new ThumbnailService(new DerivedStore(props));
    }

    @Test
    void scalesLongSideDownAndKeepsAspect() throws Exception {
        byte[] src = TestImages.plainJpeg(800, 600);

        byte[] thumb = service().render(new ByteArrayInputStream(src), 256, null);
        var img = ImageIO.read(new ByteArrayInputStream(thumb));

        assertThat(Math.max(img.getWidth(), img.getHeight())).isEqualTo(256);
        assertThat(img.getWidth()).isEqualTo(256);
        assertThat(img.getHeight()).isEqualTo(192);
    }

    @Test
    void appliesExifOrientationBeforeScaling() throws Exception {
        byte[] src = TestImages.plainJpeg(800, 600);

        byte[] thumb = service().render(new ByteArrayInputStream(src), 256, 6);
        var img = ImageIO.read(new ByteArrayInputStream(thumb));

        // Orientation=6(顺时针 90°)后长边仍是 256,但宽高互换
        assertThat(img.getWidth()).isEqualTo(192);
        assertThat(img.getHeight()).isEqualTo(256);
    }

    @Test
    void neverUpscales() throws Exception {
        byte[] src = TestImages.plainJpeg(100, 80);

        byte[] thumb = service().render(new ByteArrayInputStream(src), 512, null);
        var img = ImageIO.read(new ByteArrayInputStream(thumb));

        assertThat(img.getWidth()).isEqualTo(100);
        assertThat(img.getHeight()).isEqualTo(80);
    }

    @Test
    void generateIsIdempotentAndContentAddressed() throws Exception {
        var service = service();
        byte[] src = TestImages.plainJpeg(400, 300);

        assertThat(service.generate("aabbccdd", "photo.jpg", new ByteArrayInputStream(src), 256, null))
            .isTrue();
        assertThat(service.generate("aabbccdd", "photo.jpg", new ByteArrayInputStream(src), 256, null))
            .isFalse();  // 已存在 → 不重复生成
    }

    @Test
    void unsupportedFormatIsRejectedExplicitly() throws Exception {
        var service = service();
        byte[] src = TestImages.plainJpeg(100, 100);

        assertThatThrownBy(() -> service.generate("deadbeef", "photo.heic",
            new ByteArrayInputStream(src), 256, null))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
