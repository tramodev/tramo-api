package com.tramo.backend.upload.repository;

import com.tramo.backend.upload.entity.PublicUpload;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PublicUploadRepository extends JpaRepository<PublicUpload, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PublicUpload> findByOwnerIdAndKindAndContentHash(Long ownerId, String kind, String contentHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM PublicUpload u WHERE u.id = :id")
    Optional<PublicUpload> findForUpdate(@Param("id") UUID id);

    @Query("SELECT u.id FROM PublicUpload u WHERE u.state = 'DELETING' OR " +
            "(u.requestedAt < :cutoff AND (u.state = 'PENDING' OR u.temporaryCleaned = false)) ORDER BY u.id")
    List<UUID> findCleanupCandidates(@Param("cutoff") Date cutoff, Pageable pageable);
}
