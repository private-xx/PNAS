package com.pnas.server.iam;
import com.pnas.server.iam.domain.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface GroupRepository extends JpaRepository<Group, UUID> {
    Optional<Group> findByName(String name);
    boolean existsByName(String name);
}
