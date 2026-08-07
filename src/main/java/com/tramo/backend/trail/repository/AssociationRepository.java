package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.entity.Association;
import com.tramo.backend.trail.entity.AssociationTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface AssociationRepository extends JpaRepository<Association, Long> {
    List<Association> findBySourceItemId(Long sourceItemId);

    
    
    List<Association> findBySourceItemIdIn(Collection<Long> sourceItemIds);

    List<Association> findByTargetTypeAndTargetId(AssociationTargetType targetType, Long targetId);

    Optional<Association> findBySourceItemIdAndTargetTypeAndTargetId(
            Long sourceItemId, AssociationTargetType targetType, Long targetId);

    @Modifying(flushAutomatically = true)
    @Query("delete from Association a where a.sourceItem.id = :sourceItemId")
    void deleteBySourceItemId(@Param("sourceItemId") Long sourceItemId);

    @Modifying(flushAutomatically = true)
    @Query("delete from Association a where a.targetType = :targetType and a.targetId = :targetId")
    void deleteByTargetTypeAndTargetId(@Param("targetType") AssociationTargetType targetType, @Param("targetId") Long targetId);
}
