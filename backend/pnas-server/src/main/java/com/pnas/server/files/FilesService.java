package com.pnas.server.files;

import com.pnas.server.common.error.BusinessException;
import com.pnas.server.files.domain.Node;
import com.pnas.server.iam.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import com.pnas.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 节点树基本编排(M2;ACL 判定由后续任务注入,当前仅建/取结构)。 */
@Service
public class FilesService {

    private final NodeRepository nodes;

    public FilesService(NodeRepository nodes) {
        this.nodes = nodes;
    }

    /** 每个用户一个私有根(父节点为 null,名称:我的文件-<username>)。 */
    @Transactional
    public Node ensureUserHome(User owner) {
        return nodes.findByParentIdIsNullAndOwnerIdAndTrashedAtIsNull(owner.getId())
            .orElseGet(() -> nodes.save(Node.dir(owner, null, "我的文件-" + owner.getUsername())));
    }

    @Transactional
    public Node createDir(User owner, Node parent, String name) {
        if (parent.getKind() != Node.Kind.DIR) {
            throw new BusinessException(ErrorCode.PARENT_NOT_DIRECTORY,
                HttpStatus.BAD_REQUEST, "父节点不是目录");
        }
        if (nodes.findByParentIdAndNameAndTrashedAtIsNull(parent.getId(), name).isPresent()) {
            throw new BusinessException(ErrorCode.NAME_CONFLICT,
                HttpStatus.CONFLICT, "同目录下已存在同名节点: " + name);
        }
        return nodes.save(Node.dir(owner, parent, name));
    }

    @Transactional(readOnly = true)
    public Node requireNode(UUID nodeId) {
        return nodes.findById(nodeId).orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "节点不存在: " + nodeId));
    }
}
