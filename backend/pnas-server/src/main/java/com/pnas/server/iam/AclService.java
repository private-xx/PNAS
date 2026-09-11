package com.pnas.server.iam;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.files.NodeRepository;
import com.pnas.server.files.domain.Node;
import com.pnas.server.iam.domain.AclEntry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ACL 判定引擎(架构 v0.3 §6.2 / 需求 FR-AUTH-06)。
 *
 * 规则:
 * 1. ADMIN 短路放行;节点 owner 对其自身节点全权;
 * 2. 从目标节点沿父链向上,取**最近的、对当前主体有命中的那一级**条目集;
 * 3. 该级内**显式拒绝(perms 含 `-`)优先**;
 * 4. 全链无命中 → 拒绝(默认拒绝);
 * 5. `perms` 中 `r`/`w`/`d` = 读/写/删除,`a` = 管理权限(仅 owner/ADMIN)。
 */
@Service
public class AclService {

    private static final Set<Character> VALID = Set.of('r', 'w', 'd', '-');

    /** 权限条目 DTO(接口层用;principalType = USER|GROUP)。 */
    public record EntryDto(String principalType, UUID principalId, String perms, Boolean inherit) {}

    private final AclRepository acls;
    private final NodeRepository nodes;
    private final GroupRepository groups;
    private final UserRepository users;

    public AclService(AclRepository acls, NodeRepository nodes,
                      GroupRepository groups, UserRepository users) {
        this.acls = acls;
        this.nodes = nodes;
        this.groups = groups;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public boolean hasPermission(SessionPrincipal me, UUID nodeId, char perm) {
        if (me.isAdmin()) {
            return true;
        }
        Node node = nodes.findById(nodeId).orElse(null);
        if (node == null) {
            return false;
        }
        if (node.getOwner().getId().equals(me.userId())) {
            return true; // owner 全权
        }
        if (perm == 'a') {
            return false; // 管理权限仅 owner/ADMIN
        }
        List<UUID> groupIds = groups.findGroupIdsByUserId(me.userId());
        UUID target = node.getId();

        // 先展开 目标→根 的节点链(便于两轮判定:先 DENY,再最近命中授权)
        List<UUID> chain = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        UUID current = target;
        while (current != null && visited.add(current)) {
            chain.add(current);
            Node n = nodes.findById(current).orElse(null);
            current = (n == null || n.getParent() == null) ? null : n.getParent().getId();
        }

        // 规则 1:全链**显式拒绝优先**——祖先 DENY 不会被更近层级的 GRANT 覆盖
        for (UUID id : chain) {
            boolean denied = acls.findByNodeId(id).stream()
                .filter(e -> matches(e, me.userId(), groupIds))
                .filter(e -> applies(e, id, target))
                .anyMatch(e -> e.getPerms().contains("-"));
            if (denied) {
                return false;
            }
        }
        // 规则 2:最近的"有命中"层级决定授权
        for (UUID id : chain) {
            List<AclEntry> relevant = acls.findByNodeId(id).stream()
                .filter(e -> matches(e, me.userId(), groupIds))
                .filter(e -> applies(e, id, target))
                .toList();
            if (!relevant.isEmpty()) {
                return relevant.stream().anyMatch(e -> e.getPerms().indexOf(perm) >= 0);
            }
        }
        return false; // 默认拒绝
    }

    /**
     * 条目是否作用于目标节点:目标节点自身的条目总是生效;
     * 祖先条目仅当 {@code inherited=true} 时才向下继承(P1 评审 Critical#1:此前该字段被忽略,导致 inherit=false 仍越权授权)。
     */
    private boolean applies(AclEntry e, UUID entryNodeId, UUID targetNodeId) {
        return entryNodeId.equals(targetNodeId) || e.isInherited();
    }

    @Transactional(readOnly = true)
    public boolean canManage(SessionPrincipal me, UUID nodeId) {
        if (me.isAdmin()) {
            return true;
        }
        return nodes.findById(nodeId)
            .map(n -> n.getOwner().getId().equals(me.userId()))
            .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<EntryDto> listAcl(SessionPrincipal me, UUID nodeId) {
        requireManage(me, nodeId);
        return acls.findByNodeId(nodeId).stream()
            .map(e -> new EntryDto(e.getPrincipalType().name(), e.getPrincipalId(),
                e.getPerms(), e.isInherited()))
            .toList();
    }

    /** 整表替换某节点的 ACL(不做递归继承展开,继承由判定时沿父链实现)。 */
    @Transactional
    public List<EntryDto> setAcl(SessionPrincipal me, UUID nodeId, List<EntryDto> entries) {
        requireManage(me, nodeId);
        if (entries == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "entries 不能为空");
        }
        Node node = nodes.findById(nodeId).orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "节点不存在: " + nodeId));
        acls.deleteByNodeId(nodeId);
        List<AclEntry> toSave = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (EntryDto dto : entries) {
            validate(dto);
            String key = parseType(dto.principalType()).name() + ":" + dto.principalId();
            if (!seen.add(key)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "同一主体在本次请求中重复: " + key);
            }
            toSave.add(AclEntry.of(node, parseType(dto.principalType()), dto.principalId(),
                dto.perms(), dto.inherit() == null || dto.inherit()));
        }
        acls.saveAll(toSave);
        return listAcl(me, nodeId);
    }

    private void validate(EntryDto dto) {
        if (dto.perms() == null || dto.perms().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "perms 不能为空");
        }
        for (char c : dto.perms().toCharArray()) {
            if (!VALID.contains(c)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "非法权限字符: " + c + "(允许 r/w/d/-;管理权限 'a' 由 owner/ADMIN 隐含,不可授予)");
            }
        }
        var type = parseType(dto.principalType());
        boolean exists = type == AclEntry.PrincipalType.USER
            ? users.existsById(dto.principalId())
            : groups.existsById(dto.principalId());
        if (!exists) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND,
                "授权主体不存在: " + dto.principalId());
        }
    }

    private AclEntry.PrincipalType parseType(String raw) {
        try {
            return AclEntry.PrincipalType.valueOf(raw == null ? "" : raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "principalType 必须是 USER 或 GROUP");
        }
    }

    private void requireManage(SessionPrincipal me, UUID nodeId) {
        if (!canManage(me, nodeId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "无权管理该节点的权限");
        }
    }

    private boolean matches(AclEntry e, UUID userId, List<UUID> groupIds) {
        return e.getPrincipalType() == AclEntry.PrincipalType.USER
            ? e.getPrincipalId().equals(userId)
            : groupIds.contains(e.getPrincipalId());
    }
}
