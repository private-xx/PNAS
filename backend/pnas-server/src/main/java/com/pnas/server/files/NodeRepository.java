package com.pnas.server.files;

import com.pnas.server.files.domain.Node;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NodeRepository extends JpaRepository<Node, UUID> {
    Optional<Node> findByParentIdAndNameAndTrashedAtIsNull(UUID parentId, String name);

    Optional<Node> findByParentIdIsNullAndOwnerIdAndTrashedAtIsNull(UUID ownerId);
}
