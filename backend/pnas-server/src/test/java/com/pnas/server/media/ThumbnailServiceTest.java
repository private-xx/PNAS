package com.pnas.server.media;

import com.pnas.server.blobstore.DerivedStore;
import com.pnas.server.common.PnasProperties;
import com.pnas.server.media.testfixtures.TestImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThumbnailServiceTest {

    private static final int RED = 0xFF0000;
    private static final int GREEN = 0x00FF00;
    private static final int BLUE = 0x0000FF;
    private static final int WHITE = 0xFFFFFF;
    private static final int[] PALETTE = {RED, GREEN, BLUE, WHITE};

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

    /**
     * 像素级方向用例:8×8 四象限(红/绿/蓝/白),size=64 大于源图 → 不放大,输出仍为 8×8。
     * 期望映射按 EXIF/TIFF Orientation 定义(CIPA DC-008;libvips META_ORIENTATION):
     * 1 正常;2 水平镜像(TL→TR);3 旋转 180°(TL→BR);4 垂直镜像(TL→BL);
     * 5 转置(TL→TL,TR→BL);6 顺时针 90°(TL→TR);7 副对角线翻转(TL→BR,
     * 但 TR→TR、BL→BL,与旋转 180° 可区分);8 逆时针 90°(TL→BL)。
     */
    @ParameterizedTest(name = "orientation {0}")
    @MethodSource("orientationCorners")
    void exifOrientationMapsPixelsExactly(int orientation, int tl, int tr, int bl, int br)
            throws Exception {
        byte[] thumb = service().render(new ByteArrayInputStream(quadrantJpeg()), 64, orientation);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(thumb));

        assertThat(out.getWidth()).isEqualTo(8);    // 源小于目标尺寸 → 不放大
        assertThat(out.getHeight()).isEqualTo(8);
        assertCorner(out, 0, 0, tl);
        assertCorner(out, 7, 0, tr);
        assertCorner(out, 0, 7, bl);
        assertCorner(out, 7, 7, br);
    }

    static Stream<Arguments> orientationCorners() {
        // 源四象限:TL 红、TR 绿、BL 蓝、BR 白;参数依次为期望输出 TL/TR/BL/BR。
        return Stream.of(
            Arguments.of(1, RED, GREEN, BLUE, WHITE),
            Arguments.of(2, GREEN, RED, WHITE, BLUE),
            Arguments.of(3, WHITE, BLUE, GREEN, RED),
            Arguments.of(4, BLUE, WHITE, RED, GREEN),
            Arguments.of(5, RED, BLUE, GREEN, WHITE),
            Arguments.of(6, BLUE, RED, WHITE, GREEN),
            Arguments.of(7, WHITE, GREEN, BLUE, RED),
            Arguments.of(8, GREEN, WHITE, RED, BLUE));
    }

    @Test
    void renderedBytesAreJpeg() throws Exception {
        byte[] thumb = service().render(new ByteArrayInputStream(TestImages.plainJpeg(16, 12)), 8, null);

        assertThat(thumb.length).isGreaterThan(2);
        assertThat(thumb[0] & 0xFF).isEqualTo(0xFF);   // JPEG SOI 魔数 FF D8
        assertThat(thumb[1] & 0xFF).isEqualTo(0xD8);
    }

    /** 8×8 四象限测试图:TL 红、TR 绿、BL 蓝、BR 白。 */
    private static byte[] quadrantJpeg() throws IOException {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                img.setRGB(x, y, y < 4 ? (x < 4 ? RED : GREEN) : (x < 4 ? BLUE : WHITE));
            }
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /** JPEG 有损压缩后按最近邻归类到四个象限颜色,再校验偏离不超过 60/通道。 */
    private static void assertCorner(BufferedImage image, int x, int y, int expected) {
        int actual = image.getRGB(x, y);
        assertThat(nearest(actual)).as("corner (%d,%d) rgb=#%06X", x, y, actual & 0xFFFFFF)
            .isEqualTo(expected);
        for (int shift : new int[]{16, 8, 0}) {
            int delta = Math.abs(((actual >> shift) & 0xFF) - ((expected >> shift) & 0xFF));
            assertThat(delta).as("corner (%d,%d) channel %d", x, y, shift).isLessThanOrEqualTo(60);
        }
    }

    private static int nearest(int rgb) {
        int best = PALETTE[0];
        int bestDistance = Integer.MAX_VALUE;
        for (int color : PALETTE) {
            int dr = ((rgb >> 16) & 0xFF) - ((color >> 16) & 0xFF);
            int dg = ((rgb >> 8) & 0xFF) - ((color >> 8) & 0xFF);
            int db = (rgb & 0xFF) - (color & 0xFF);
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = color;
            }
        }
        return best;
    }
}
