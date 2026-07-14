package com.mypath.backend.path.repository;

import com.mypath.backend.path.entity.PathIdea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PathIdeaRepository extends JpaRepository<PathIdea, Long> {
    List<PathIdea> findByPathIdOrderByOrderIndexAsc(Long pathId);
    List<PathIdea> findByIdeaId(Long ideaId);
    int countByPathId(Long pathId);

    @Query("SELECT pi FROM PathIdea pi JOIN FETCH pi.idea i LEFT JOIN FETCH i.content WHERE pi.path.id IN :pathIds ORDER BY pi.orderIndex ASC")
    List<PathIdea> findByPathIdInWithIdeaAndContent(@Param("pathIds") List<Long> pathIds);
}
