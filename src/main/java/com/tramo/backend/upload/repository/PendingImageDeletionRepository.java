package com.tramo.backend.upload.repository;

import com.tramo.backend.upload.entity.PendingImageDeletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public interface PendingImageDeletionRepository extends JpaRepository<PendingImageDeletion, Long> {
    boolean existsByUrl(String url);
    List<PendingImageDeletion> findByRequestedAtBefore(Date cutoff);
}
