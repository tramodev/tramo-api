package com.mypath.backend.path.repository;

import com.mypath.backend.path.entity.PathIdea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PathIdeaRepository extends JpaRepository<PathIdea, Long> {
    List<PathIdea> findByPathIdOrderByOrderIndexAsc(Long pathId);
    List<PathIdea> findByIdeaId(Long ideaId);
    int countByPathId(Long pathId);
}
