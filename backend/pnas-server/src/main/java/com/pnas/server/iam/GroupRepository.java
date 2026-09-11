package com.pnas.server.iam;
import com.pnas.server.iam.domain.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupRepository extends JpaRepository<Group, UUID> {
    Optional<Group> findByName(String name);
    boolean existsByName(String name);

    /** 用户所属的组 id 列表(ACL 判定用)。 */
    @Query("select g.id from Group g join g.users u where u.id = :userId")
    List<UUID> findGroupIdsByUserId(@Param("userId") UUID userId);
}
