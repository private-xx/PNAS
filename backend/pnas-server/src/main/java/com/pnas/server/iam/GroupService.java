package com.pnas.server.iam;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.iam.domain.Group;
import com.pnas.server.iam.domain.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 组管理(FR-AUTH-05):组是 ACL 的授权主体之一。 */
@Service
public class GroupService {

    public record MemberDto(UUID id, String username) {}
    public record GroupDto(UUID id, String name, List<MemberDto> members) {}

    private final GroupRepository groups;
    private final UserRepository users;

    public GroupService(GroupRepository groups, UserRepository users) {
        this.groups = groups;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<GroupDto> list() {
        return groups.findAll().stream().map(this::toDto).toList();
    }

    @Transactional
    public GroupDto create(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "组名不能为空");
        }
        if (groups.existsByName(clean)) {
            throw new BusinessException(ErrorCode.NAME_CONFLICT, HttpStatus.CONFLICT, "组已存在: " + clean);
        }
        return toDto(groups.save(Group.create(clean)));
    }

    @Transactional
    public GroupDto addMember(UUID groupId, UUID userId) {
        Group g = requireGroup(groupId);
        User u = users.findById(userId).orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "用户不存在: " + userId));
        g.getUsers().add(u);
        return toDto(groups.save(g));
    }

    @Transactional
    public GroupDto removeMember(UUID groupId, UUID userId) {
        Group g = requireGroup(groupId);
        g.getUsers().removeIf(u -> u.getId().equals(userId));
        return toDto(groups.save(g));
    }

    private Group requireGroup(UUID groupId) {
        return groups.findById(groupId).orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "组不存在: " + groupId));
    }

    private GroupDto toDto(Group g) {
        return new GroupDto(g.getId(), g.getName(),
            g.getUsers().stream().map(u -> new MemberDto(u.getId(), u.getUsername())).toList());
    }
}
