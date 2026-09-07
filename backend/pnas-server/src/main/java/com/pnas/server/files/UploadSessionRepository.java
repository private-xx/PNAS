package com.pnas.server.files;

import com.pnas.server.files.domain.UploadSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {
}
