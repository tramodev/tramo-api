package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.entity.Trail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TrailRepository extends JpaRepository<Trail, Long> {
    
    
    
    @Query("select t from Trail t join fetch t.project where t.project.id = :projectId order by t.id asc")
    List<Trail> findByProjectId(@Param("projectId") Long projectId);

    // No join fetch here: Project.owner is EAGER (unlike Trail.project, which is LAZY),
    // so eagerly loading Project per row would N+1-load its owner. Trail.project stays a
    // lazy proxy — callers only need .getId() off it, which doesn't hit the DB.
    @Query("select t from Trail t where t.project.id in :projectIds order by t.project.id asc, t.id asc")
    List<Trail> findByProjectIdIn(@Param("projectIds") Collection<Long> projectIds);

    
    
    @Query("select t from Trail t join fetch t.project where t.id = :id")
    Optional<Trail> findByIdWithProject(@Param("id") Long id);

    
    
    @Query("SELECT t.id, t.title FROM Trail t WHERE t.id IN :ids")
    List<Object[]> findIdTitleByIdIn(@Param("ids") Collection<Long> ids);
}
