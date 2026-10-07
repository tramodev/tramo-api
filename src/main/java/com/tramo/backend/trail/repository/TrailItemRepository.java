// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.entity.TrailItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrailItemRepository extends JpaRepository<TrailItem, Long> {
    
    
    
    
    
    @Query("SELECT pi FROM TrailItem pi JOIN FETCH pi.item i LEFT JOIN FETCH i.content " +
            "LEFT JOIN FETCH pi.association WHERE pi.trail.id = :trailId ORDER BY pi.orderIndex ASC, pi.id ASC")
    List<TrailItem> findByTrailIdOrderByOrderIndexAsc(@Param("trailId") Long trailId);

    
    
    @Query("SELECT pi FROM TrailItem pi JOIN FETCH pi.trail t LEFT JOIN FETCH t.project WHERE pi.item.id = :itemId")
    List<TrailItem> findByItemId(@Param("itemId") Long itemId);

    @Query("SELECT membership FROM TrailItem membership JOIN FETCH membership.trail t JOIN FETCH t.project p " +
            "WHERE p.owner.id = :ownerId AND (membership.item.project.id = :projectId OR membership.item.id IN " +
            "(SELECT local.item.id FROM TrailItem local WHERE local.trail.project.id = :projectId)) ORDER BY t.id")
    List<TrailItem> findMembershipsForProject(@Param("projectId") Long projectId, @Param("ownerId") Long ownerId);

    Optional<TrailItem> findFirstByTrailIdOrderByOrderIndexAscIdAsc(Long trailId);

    int countByTrailId(Long trailId);

    @Query("SELECT pi FROM TrailItem pi LEFT JOIN FETCH pi.association WHERE pi.trail.id = :trailId AND pi.item.id = :itemId")
    Optional<TrailItem> findByTrailIdAndItemId(@Param("trailId") Long trailId, @Param("itemId") Long itemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT step FROM TrailItem step WHERE step.trail.id = :trailId AND step.item.id = :itemId")
    Optional<TrailItem> findForReplacement(@Param("trailId") Long trailId, @Param("itemId") Long itemId);

    boolean existsByTrailIdAndItemId(Long trailId, Long itemId);

    @Query("SELECT pi FROM TrailItem pi JOIN FETCH pi.item i LEFT JOIN FETCH i.content WHERE pi.trail.id IN :trailIds ORDER BY pi.orderIndex ASC, pi.id ASC")
    List<TrailItem> findByTrailIdInWithItemAndContent(@Param("trailIds") List<Long> trailIds);

    
    
    @Query("SELECT pi FROM TrailItem pi JOIN FETCH pi.item i LEFT JOIN FETCH i.content " +
            "LEFT JOIN FETCH pi.association WHERE pi.trail.id IN :trailIds ORDER BY pi.trail.id ASC, pi.orderIndex ASC, pi.id ASC")
    List<TrailItem> findByTrailIdInWithItemContentAndAssociation(@Param("trailIds") List<Long> trailIds);
}
