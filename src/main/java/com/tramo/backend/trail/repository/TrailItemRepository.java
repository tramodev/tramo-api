package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.entity.TrailItem;
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

    int countByTrailId(Long trailId);

    @Query("SELECT pi FROM TrailItem pi LEFT JOIN FETCH pi.association WHERE pi.trail.id = :trailId AND pi.item.id = :itemId")
    Optional<TrailItem> findByTrailIdAndItemId(@Param("trailId") Long trailId, @Param("itemId") Long itemId);

    boolean existsByTrailIdAndItemId(Long trailId, Long itemId);

    @Query("SELECT pi FROM TrailItem pi JOIN FETCH pi.item i LEFT JOIN FETCH i.content WHERE pi.trail.id IN :trailIds ORDER BY pi.orderIndex ASC, pi.id ASC")
    List<TrailItem> findByTrailIdInWithItemAndContent(@Param("trailIds") List<Long> trailIds);

    
    
    @Query("SELECT pi FROM TrailItem pi JOIN FETCH pi.item i LEFT JOIN FETCH i.content " +
            "LEFT JOIN FETCH pi.association WHERE pi.trail.id IN :trailIds ORDER BY pi.trail.id ASC, pi.orderIndex ASC, pi.id ASC")
    List<TrailItem> findByTrailIdInWithItemContentAndAssociation(@Param("trailIds") List<Long> trailIds);
}
