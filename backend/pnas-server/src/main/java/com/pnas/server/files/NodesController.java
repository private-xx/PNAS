package com.pnas.server.files;

import com.pnas.server.auth.SessionPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/** 目录/文件 REST 接口(架构 §7;FR-FS-01/02/03/04)。所有判定在服务端完成。 */
@RestController
@RequestMapping("/api/v1/nodes")
public class NodesController {

    private final FilesService files;

    public NodesController(FilesService files) {
        this.files = files;
    }

    public record CreateDir(UUID parentId, String name) {}
    public record Rename(String name) {}

    @GetMapping
    public List<FilesService.NodeDto> list(@AuthenticationPrincipal SessionPrincipal me,
                                           @RequestParam(required = false) UUID parentId,
                                           @RequestParam(name = "trash", defaultValue = "false") boolean trash) {
        return trash ? files.listTrash(me) : files.list(me, parentId);
    }

    @PostMapping
    public FilesService.NodeDto createDir(@AuthenticationPrincipal SessionPrincipal me,
                                          @RequestBody CreateDir req) {
        return files.createDir(me, req.parentId(), req.name());
    }

    @PatchMapping("/{id}")
    public FilesService.NodeDto rename(@AuthenticationPrincipal SessionPrincipal me,
                                       @PathVariable UUID id, @RequestBody Rename req) {
        return files.rename(me, id, req.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trash(@AuthenticationPrincipal SessionPrincipal me, @PathVariable UUID id) {
        files.trash(me, id);
    }

    @PostMapping("/{id}/restore")
    public FilesService.NodeDto restore(@AuthenticationPrincipal SessionPrincipal me,
                                        @PathVariable UUID id) {
        return files.restore(me, id);
    }

    @GetMapping("/{id}/versions")
    public List<FilesService.VersionDto> versions(@AuthenticationPrincipal SessionPrincipal me,
                                                  @PathVariable UUID id) {
        return files.listVersions(me, id);
    }

    /** 下载(支持单段 Range:bytes=start-end / bytes=start-)。 */
    @GetMapping("/{id}/content")
    public ResponseEntity<StreamingResponseBody> content(
            @AuthenticationPrincipal SessionPrincipal me,
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {

        long total = files.sizeOf(me, id);
        boolean ranged = range != null && !range.isBlank();
        long start = 0;
        Long end = null;
        if (ranged) {
            if (!range.startsWith("bytes=")) {
                throw badRange("仅支持 bytes 单位: " + range);
            }
            String spec = range.substring("bytes=".length()).trim();
            String[] parts = spec.split("-", 2);
            try {
                if (parts[0].isEmpty()) {
                    // 后缀范围 bytes=-N:最后 N 字节
                    long suffix = Long.parseLong(parts.length > 1 ? parts[1].trim() : "");
                    start = Math.max(0, total - suffix);
                    end = total - 1;
                } else {
                    start = Long.parseLong(parts[0].trim());
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Long.parseLong(parts[1].trim());
                    }
                }
            } catch (NumberFormatException e) {
                throw badRange("Range 数值无法解析: " + range);
            }
            if (start < 0 || start >= total || (end != null && end < start)) {
                throw new com.pnas.server.common.error.BusinessException(
                    com.pnas.common.error.ErrorCode.BAD_REQUEST,
                    HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "Range 越界: " + range);
            }
        }
        long length = total - start;
        if (end != null) {
            length = Math.min(length, Math.max(0, end - start + 1));
        }

        var cs = files.content(me, id, start, length);
        var builder = ResponseEntity.status(ranged ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
            .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(cs.length()))
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .contentType(MediaType.APPLICATION_OCTET_STREAM);
        if (ranged) {
            builder.header(HttpHeaders.CONTENT_RANGE,
                "bytes " + start + "-" + (start + cs.length() - 1) + "/" + total);
        }
        StreamingResponseBody body = out -> {
            try (InputStream in = cs.stream()) {
                in.transferTo(out);
            }
        };
        return builder.body(body);
    }

    private com.pnas.server.common.error.BusinessException badRange(String message) {
        return new com.pnas.server.common.error.BusinessException(
            com.pnas.common.error.ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, message);
    }
}
