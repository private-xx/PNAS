package com.pnas.server.files;

import com.pnas.server.files.domain.UploadSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {

    /** 行级悲观锁:串行化同一会话的 complete,避免并发下创建重复版本。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UploadSession s where s.id = :id")
    Optional<UploadSession> findByIdForUpdate(@Param("id") UUID id);
}
