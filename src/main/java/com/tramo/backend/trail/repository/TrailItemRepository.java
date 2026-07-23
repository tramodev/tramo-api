package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.entity.TrailItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrailItemRepository extends JpaRepository<TrailItem, Long> {
    // Secondary id sort: attach sets orderIndex = count, so after a detach two
    // rows can share an index — without a tiebreak their order flips between
    // reloads. id asc = attach order, matching the frontend's append.
    @Query("SELECT pi FROM TrailItem pi WHERE pi.trail.id = :trailId ORDER BY pi.orderIndex ASC, pi.id ASC")
    List<TrailItem> findByTrailIdOrderByOrderIndexAsc(@Param("trailId") Long trailId);

    List<TrailItem> findByItemId(Long itemId);
    int countByTrailId(Long trailId);

    @Query("SELECT pi FROM TrailItem pi JOIN FETCH pi.item i LEFT JOIN FETCH i.content WHERE pi.trail.id IN :trailIds ORDER BY pi.orderIndex ASC, pi.id ASC")
    List<TrailItem> findByTrailIdInWithItemAndContent(@Param("trailIds") List<Long> trailIds);

    @Query("SELECT COUNT(pi) > 0 FROM TrailItem pi WHERE pi.trail.project.owner.id = :ownerId " +
            "AND pi.item.content.content LIKE %:url% AND pi.item.id <> :excludeItemId")
    boolean existsOtherItemReferencingUrl(@Param("ownerId") Long ownerId, @Param("url") String url, @Param("excludeItemId") Long excludeItemId);
}
