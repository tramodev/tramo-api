// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.upload.repository;

import com.tramo.backend.upload.entity.PendingImageDeletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public interface PendingImageDeletionRepository extends JpaRepository<PendingImageDeletion, Long> {
    boolean existsByUrl(String url);
    List<PendingImageDeletion> findByRequestedAtBefore(Date cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PendingImageDeletion> findByUrl(String url);

    @Transactional
    @Modifying
    @Query("UPDATE PendingImageDeletion p SET p.requestedAt = :now WHERE p.url = :url")
    int postpone(@Param("url") String url, @Param("now") Date now);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM item_image_reference WHERE url = :url) " +
            "OR EXISTS (SELECT 1 FROM item_content WHERE strpos(content, :url) > 0) " +
            "OR EXISTS (SELECT 1 FROM project_version WHERE strpos(snapshot, :url) > 0) " +
            "OR EXISTS (SELECT 1 FROM project_snapshot WHERE strpos(content, :url) > 0) " +
            "OR EXISTS (SELECT 1 FROM project WHERE thumbnail_image_url = :url) " +
            "OR EXISTS (SELECT 1 FROM users WHERE image_url = :url OR banner_url = :url)", nativeQuery = true)
    boolean isReferencedGlobally(@Param("url") String url);
}
