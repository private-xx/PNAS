package com.pnas.server.media.testfixtures;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 测试素材:构造可控 EXIF 的 JPEG(不依赖外部工具或二进制 fixture)。 */
public final class TestImages {

    private TestImages() {}

    /** 无 EXIF 的纯 JPEG。 */
    public static byte[] plainJpeg(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                img.setRGB(x, y, ((x * 7) << 16) | ((y * 13) << 8) | 0x40);
            }
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /** 带 EXIF 的 JPEG:Orientation + DateTimeOriginal(UTC)+ GPS 经纬度。 */
    public static byte[] jpegWithExif(Instant takenAt, double lat, double lon, int orientation,
                                      int width, int height) throws Exception {
        byte[] base = plainJpeg(width, height);
        byte[] app1 = exifApp1(takenAt, lat, lon, orientation);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(base, 0, 2);          // SOI
        out.write(app1);                // APP1(EXIF)
        out.write(base, 2, base.length - 2);
        return out.toByteArray();
    }

    private static byte[] exifApp1(Instant takenAt, double lat, double lon, int orientation) {
        String dt = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
            .withZone(ZoneOffset.UTC).format(takenAt);
        byte[] dtBytes = (dt + "\0").getBytes(StandardCharsets.US_ASCII);   // 20 字节

        // 偏移布局(相对 TIFF 头起点):头 8 + IFD0(3 项:2+3*12+4=42) + ExifIFD(1 项:2+12+4=18) + GPS IFD(4 项:2+48+4=54)
        final int ifd0Offset = 8;
        final int exifIfdOffset = ifd0Offset + 42;
        final int gpsIfdOffset = exifIfdOffset + 18;
        final int dataOffset = gpsIfdOffset + 54;      // 之后依次:dtBytes, lat rationals, lon rationals
        final int latOffset = dataOffset + dtBytes.length;
        final int lonOffset = latOffset + 24;

        ByteBuffer b = ByteBuffer.allocate(dataOffset + dtBytes.length + 48).order(ByteOrder.LITTLE_ENDIAN);
        // TIFF 头
        b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd0Offset);
        // IFD0:Orientation + Exif SubIFD 指针 + GPS IFD 指针
        b.putShort((short) 3);
        b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        b.putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(exifIfdOffset);
        b.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsIfdOffset);
        b.putInt(0);
        // Exif SubIFD(ExifIFD 大小 = 2 + 1*12 + 4)
        b.putShort((short) 1);
        b.putShort((short) 0x9003).putShort((short) 2).putInt(dtBytes.length).putInt(dataOffset);
        b.putInt(0);
        // GPS IFD
        b.putShort((short) 4);
        // ASCII 值域固定 4 字节:参考字符 + NUL + 2 字节零填充
        b.putShort((short) 0x0001).putShort((short) 2).putInt(2)
            .put((byte) (lat >= 0 ? 'N' : 'S')).put((byte) 0).putShort((short) 0);
        b.putShort((short) 0x0002).putShort((short) 5).putInt(3).putInt(latOffset);
        b.putShort((short) 0x0003).putShort((short) 2).putInt(2)
            .put((byte) (lon >= 0 ? 'E' : 'W')).put((byte) 0).putShort((short) 0);
        b.putShort((short) 0x0004).putShort((short) 5).putInt(3).putInt(lonOffset);
        b.putInt(0);
        // 数据区
        b.put(dtBytes);
        putRationals(b, Math.abs(lat));
        putRationals(b, Math.abs(lon));

        byte[] tiff = b.array();
        ByteBuffer app1 = ByteBuffer.allocate(tiff.length + 10).order(ByteOrder.BIG_ENDIAN);
        app1.put((byte) 0xFF).put((byte) 0xE1);
        app1.putShort((short) (tiff.length + 8));       // 长度含自身 2 字节
        app1.put("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        app1.put(tiff);
        return app1.array();
    }

    /** 度/分/秒 三个 RATIONAL。 */
    private static void putRationals(ByteBuffer b, double deg) {
        int d = (int) deg;
        double minutesFull = (deg - d) * 60;
        int m = (int) minutesFull;
        double sec = (minutesFull - m) * 60;
        b.putInt(d).putInt(1);
        b.putInt(m).putInt(1);
        b.putInt((int) Math.round(sec * 10000)).putInt(10000);
    }
}
