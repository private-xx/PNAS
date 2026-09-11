package com.pnas.server.iam;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.common.error.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 组管理接口(FR-AUTH-05):仅 ADMIN;组作为 ACL 授权主体参与权限判定。 */
@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

    private final GroupService groups;

    public GroupController(GroupService groups) {
        this.groups = groups;
    }

    public record CreateGroup(String name) {}

    @GetMapping
    public List<GroupService.GroupDto> list(@AuthenticationPrincipal SessionPrincipal me) {
        requireAdmin(me);
        return groups.list();
    }

    @PostMapping
    public GroupService.GroupDto create(@AuthenticationPrincipal SessionPrincipal me,
                                        @RequestBody CreateGroup req) {
        requireAdmin(me);
        return groups.create(req.name());
    }

    @PostMapping("/{id}/members")
    public GroupService.GroupDto addMember(@AuthenticationPrincipal SessionPrincipal me,
                                           @PathVariable UUID id,
                                           @RequestBody Map<String, UUID> body) {
        requireAdmin(me);
        UUID userId = body == null ? null : body.get("userId");
        if (userId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "缺少 userId");
        }
        return groups.addMember(id, userId);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public GroupService.GroupDto removeMember(@AuthenticationPrincipal SessionPrincipal me,
                                              @PathVariable UUID id,
                                              @PathVariable UUID userId) {
        requireAdmin(me);
        return groups.removeMember(id, userId);
    }

    private void requireAdmin(SessionPrincipal me) {
        if (!me.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "仅管理员可管理组");
        }
    }
}
