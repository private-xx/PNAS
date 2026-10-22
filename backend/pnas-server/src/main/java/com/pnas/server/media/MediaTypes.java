package com.pnas.server.media;

import com.pnas.server.media.domain.MediaKind;

import java.util.Locale;
import java.util.Set;

/** 媒体类型判定(spec §5):扩展名与 MIME 双通道;判定为媒体才入库。 */
public final class MediaTypes {

    /** 可用纯 Java ImageIO 解码的图片格式(M3 可生成缩略图) */
    private static final Set<String> DECODABLE_IMAGE_EXT =
        Set.of("jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff");
    /** 所有识别为图片的格式(含 M3 不生成缩略图的 HEIC/WEBP) */
    private static final Set<String> IMAGE_EXT =
        Set.of("jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff", "heic", "heif", "webp", "avif");
    private static final Set<String> VIDEO_EXT =
        Set.of("mp4", "m4v", "mov", "webm", "mkv", "avi");

    private MediaTypes() {}

    /** 非媒体返回 null。 */
    public static MediaKind classify(String filename, String mimeType) {
        String ext = extension(filename);
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (IMAGE_EXT.contains(ext) || mime.startsWith("image/")) {
            return MediaKind.IMAGE;
        }
        if (VIDEO_EXT.contains(ext) || mime.startsWith("video/")) {
            return MediaKind.VIDEO;
        }
        return null;
    }

    /** 能否生成缩略图:M3 仅支持纯 Java 可解码的图片;其余入库但置 SKIPPED(spec D3)。 */
    public static boolean canThumbnail(MediaKind kind, String filename) {
        return kind == MediaKind.IMAGE && DECODABLE_IMAGE_EXT.contains(extension(filename));
    }

    static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
