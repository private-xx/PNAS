package com.pnas.server.iam;

import com.pnas.server.iam.domain.AclEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AclRepository extends JpaRepository<AclEntry, UUID> {
    List<AclEntry> findByNodeId(UUID nodeId);
    void deleteByNodeId(UUID nodeId);
    long countByNodeId(UUID nodeId);
}
