package com.pnas.server.media;

import com.pnas.server.blobstore.DerivedStore;
import com.pnas.server.media.domain.MediaKind;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 缩略图生成:解码 → 按 Orientation 旋转/镜像 → 长边缩放(不放大)→ JPEG 0.82(spec §5)。 */
@Component
public class ThumbnailService {

    private static final float JPEG_QUALITY = 0.82f;

    private final DerivedStore derived;

    public ThumbnailService(DerivedStore derived) {
        this.derived = derived;
    }

    /** 生成并落盘;已存在返回 false;格式不支持抛 UnsupportedOperationException。 */
    public boolean generate(String versionSha, String filename, InputStream original, int size,
                            Integer orientation) throws IOException {
        if (derived.exists(versionSha, size)) {
            return false;
        }
        if (!MediaTypes.canThumbnail(MediaKind.IMAGE, filename)) {
            throw new UnsupportedOperationException("该格式不支持缩略图: " + filename);
        }
        derived.writeAtomic(versionSha, size, render(original, size, orientation));
        return true;
    }

    /** 渲染为 JPEG 字节。 */
    public byte[] render(InputStream original, int size, Integer orientation) throws IOException {
        BufferedImage src = ImageIO.read(original);
        if (src == null) {
            throw new IOException("图像无法解码(可能损坏或非图片)");
        }
        BufferedImage oriented = orient(src, orientation == null ? 1 : orientation);
        int longSide = Math.max(oriented.getWidth(), oriented.getHeight());
        double scale = longSide <= size ? 1.0 : (double) size / longSide;
        int targetW = Math.max(1, (int) Math.round(oriented.getWidth() * scale));
        int targetH = Math.max(1, (int) Math.round(oriented.getHeight() * scale));

        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(oriented, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        return toJpeg(out);
    }

    /**
     * 按 EXIF Orientation(1–8)修正像素方向:2/3/4 为镜像/旋转 180°,
     * 5–8 交换宽高(5 转置、6 顺时针 90°、7 副对角线翻转、8 逆时针 90°)。
     */
    private BufferedImage orient(BufferedImage src, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, transform(orientation, w, h), null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 源坐标 → 目标坐标的仿射变换(AffineTransform 构造参数为 m00, m10, m01, m11, m02, m12)。 */
    private AffineTransform transform(int orientation, int w, int h) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);    // 水平镜像
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);   // 旋转 180°
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);    // 垂直镜像
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);     // 转置
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);    // 顺时针 90°
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);   // 副对角线翻转
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);    // 逆时针 90°
            default -> new AffineTransform();
        };
    }

    private byte[] toJpeg(BufferedImage image) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("jpg").next();
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        var out = new ByteArrayOutputStream();
        try (var ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
