package com.pnas.server.files;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.iam.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

/** 分块上传三端点(架构 §6.3;FR-FS-02)。写权限在服务端校验,分块哈希由 X-Sha256 头携带。 */
@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {

    private final UploadService uploads;
    private final FilesService files;
    private final UserRepository users;

    public UploadController(UploadService uploads, FilesService files, UserRepository users) {
        this.uploads = uploads;
        this.files = files;
        this.users = users;
    }

    public record StartUpload(UUID destParentId, String filename, long size) {}

    @PostMapping
    public Map<String, UUID> start(@AuthenticationPrincipal SessionPrincipal me,
                                   @RequestBody StartUpload req) {
        files.assertCan(me, req.destParentId(), 'w');
        var owner = users.findById(me.userId()).orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "用户不存在"));
        UUID uploadId = uploads.start(owner, req.destParentId(), req.filename(), req.size());
        return Map.of("uploadId", uploadId);
    }

    @PutMapping(value = "/{uploadId}/chunks/{seq}", consumes = MediaType.ALL_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void chunk(@AuthenticationPrincipal SessionPrincipal me,
                      @PathVariable UUID uploadId,
                      @PathVariable int seq,
                      @RequestHeader("X-Sha256") String sha256,
                      InputStream body) {
        uploads.acceptChunk(me.userId(), uploadId, seq, body, sha256);
    }

    @PostMapping("/{uploadId}/complete")
    public UploadService.Completed complete(@AuthenticationPrincipal SessionPrincipal me,
                                            @PathVariable UUID uploadId) {
        return uploads.complete(me.userId(), uploadId);
    }

    @PostMapping("/{uploadId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal SessionPrincipal me, @PathVariable UUID uploadId) {
        uploads.cancel(me.userId(), uploadId);
    }
}
