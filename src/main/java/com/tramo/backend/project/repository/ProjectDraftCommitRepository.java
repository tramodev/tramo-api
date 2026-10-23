package com.tramo.backend.project.repository;

import com.tramo.backend.project.entity.ProjectDraftCommit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ProjectDraftCommitRepository extends JpaRepository<ProjectDraftCommit, Long> {
    Optional<ProjectDraftCommit> findByUserIdAndClientRequestId(Long userId, UUID clientRequestId);
    long deleteByCreatedAtBefore(java.util.Date cutoff);
}
