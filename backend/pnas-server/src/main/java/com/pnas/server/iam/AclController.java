package com.pnas.server.iam;

import com.pnas.server.auth.SessionPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 节点 ACL 管理接口(FR-AUTH-06):仅 owner 或 ADMIN 可读/写管理。 */
@RestController
@RequestMapping("/api/v1/nodes/{nodeId}/acl")
public class AclController {

    private final AclService acl;

    public AclController(AclService acl) {
        this.acl = acl;
    }

    @GetMapping
    public List<AclService.EntryDto> get(@AuthenticationPrincipal SessionPrincipal me,
                                         @PathVariable UUID nodeId) {
        return acl.listAcl(me, nodeId);
    }

    @PutMapping
    public List<AclService.EntryDto> put(@AuthenticationPrincipal SessionPrincipal me,
                                         @PathVariable UUID nodeId,
                                         @RequestBody List<AclService.EntryDto> entries) {
        return acl.setAcl(me, nodeId, entries);
    }
}
