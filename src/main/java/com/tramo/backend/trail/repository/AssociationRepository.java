package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.entity.Association;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssociationRepository extends JpaRepository<Association, Long> {
    List<Association> findBySourceItemId(Long sourceItemId);
    List<Association> findBySourceItemIdIn(Collection<Long> sourceItemIds);
    Optional<Association> findBySourceItemIdAndTargetItemId(Long sourceItemId, Long targetId);

    @Modifying(flushAutomatically = true)
    @Query("delete from Association a where a.sourceItem.id = :id")
    void deleteBySourceItemId(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("delete from Association a where a.targetItem.id = :id")
    void deleteByTargetItemId(@Param("id") Long id);
}
