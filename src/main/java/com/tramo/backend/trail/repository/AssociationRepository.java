package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.entity.Association;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssociationRepository extends JpaRepository<Association, Long> {
    List<Association> findBySourceItemIdOrTargetItemId(Long sourceItemId, Long targetItemId);
    void deleteBySourceItemIdOrTargetItemId(Long sourceItemId, Long targetItemId);
    Optional<Association> findBySourceItemIdAndTargetItemId(Long sourceItemId, Long targetItemId);
}
