package com.pnas.server.media;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

/** 读取 EXIF(拍摄时间/Orientation/GPS/尺寸);任何解析失败都降级为空结果,不抛异常(spec §7)。 */
@Component
public class ExifReader {

    public record ExifData(Instant takenAt, Integer width, Integer height,
                           Double gpsLat, Double gpsLon, Integer orientation) {
        public static ExifData empty() {
            return new ExifData(null, null, null, null, null, null);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ExifReader.class);

    public ExifData read(InputStream in) {
        try {
            Metadata md = ImageMetadataReader.readMetadata(in);
            Instant takenAt = null;
            ExifSubIFDDirectory sub = md.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (sub != null) {
                // EXIF DateTimeOriginal 是无时区的本地墙钟时间:按 JVM 默认时区解释
                // (单时区家庭 NAS;可配置时区延后到 M5)
                TimeZone zone = TimeZone.getDefault();
                Date original = zone != null ? sub.getDateOriginal(zone) : sub.getDateOriginal();
                if (original != null) {
                    takenAt = original.toInstant();
                }
            }
            Integer width = null;
            Integer height = null;
            Integer orientation = null;
            ExifIFD0Directory ifd0 = md.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null) {
                orientation = ifd0.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
                width = ifd0.getInteger(ExifIFD0Directory.TAG_IMAGE_WIDTH);
                height = ifd0.getInteger(ExifIFD0Directory.TAG_IMAGE_HEIGHT);
            }
            JpegDirectory jpeg = md.getFirstDirectoryOfType(JpegDirectory.class);
            if (jpeg != null) {
                if (width == null) {
                    width = jpeg.getImageWidth();
                }
                if (height == null) {
                    height = jpeg.getImageHeight();
                }
            }
            Double lat = null;
            Double lon = null;
            GpsDirectory gps = md.getFirstDirectoryOfType(GpsDirectory.class);
            if (gps != null && gps.getGeoLocation() != null) {
                lat = gps.getGeoLocation().getLatitude();
                lon = gps.getGeoLocation().getLongitude();
            }
            return new ExifData(takenAt, width, height, lat, lon, orientation);
        } catch (Exception e) {
            log.debug("EXIF 解析失败,按无元数据处理: {}", e.getMessage());
            return ExifData.empty();
        }
    }
}
