package com.pnas.server.files;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.common.error.BusinessException;
import org.springframework.http.HttpStatus;

/** HTTP Range 解析(single range):与 M2 的下载行为完全一致,供节点与媒体端点共用。 */
public record RangeSpec(long start, long length, boolean ranged) {

    public static RangeSpec parse(String header, long totalSize) {
        if (header == null || header.isBlank()) {
            return new RangeSpec(0, totalSize, false);
        }
        if (!header.startsWith("bytes=")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "仅支持 bytes 单位的 Range: " + header);
        }
        String spec = header.substring("bytes=".length()).trim();
        if (spec.contains(",")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "仅支持单段 Range");
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "Range 格式不合法: " + header);
        }
        String first = spec.substring(0, dash).trim();
        String last = spec.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (first.isEmpty()) {
                long suffix = Long.parseLong(last);
                if (suffix <= 0) {
                    throw outOfRange();
                }
                start = Math.max(0, totalSize - suffix);
                end = totalSize - 1;
            } else {
                start = Long.parseLong(first);
                end = last.isEmpty() ? totalSize - 1 : Long.parseLong(last);
            }
            if (start < 0 || start >= totalSize) {
                throw outOfRange();
            }
            end = Math.min(end, totalSize - 1);
            if (end < start) {
                throw outOfRange();
            }
            return new RangeSpec(start, end - start + 1, true);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "Range 数字不合法: " + header);
        }
    }

    private static BusinessException outOfRange() {
        return new BusinessException(ErrorCode.BAD_REQUEST,
            HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "Range 越界");
    }
}
