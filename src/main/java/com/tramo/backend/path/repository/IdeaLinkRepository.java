package com.tramo.backend.path.repository;

import com.tramo.backend.path.entity.IdeaLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IdeaLinkRepository extends JpaRepository<IdeaLink, Long> {
    List<IdeaLink> findBySourceIdeaIdOrTargetIdeaId(Long sourceIdeaId, Long targetIdeaId);
    void deleteBySourceIdeaIdOrTargetIdeaId(Long sourceIdeaId, Long targetIdeaId);
    Optional<IdeaLink> findBySourceIdeaIdAndTargetIdeaId(Long sourceIdeaId, Long targetIdeaId);
}
