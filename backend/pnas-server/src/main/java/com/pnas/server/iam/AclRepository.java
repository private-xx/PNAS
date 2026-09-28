package com.pnas.server.iam;

import com.pnas.server.iam.domain.AclEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AclRepository extends JpaRepository<AclEntry, UUID> {
    List<AclEntry> findByNodeId(UUID nodeId);
    void deleteByNodeId(UUID nodeId);
    long countByNodeId(UUID nodeId);

    /** 删除某主体(用户/组)的全部授权条目:组被删除时避免悬挂授权。 */
    void deleteByPrincipalTypeAndPrincipalId(AclEntry.PrincipalType principalType, UUID principalId);
}
