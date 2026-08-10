package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.entity.Trail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    @Query("select t from Trail t where t.project.id in :projectIds order by t.project.id asc, t.id asc")
    List<Trail> findByProjectIdIn(@Param("projectIds") Collection<Long> projectIds);

    
    
    @Query("select t from Trail t join fetch t.project where t.id = :id")
    Optional<Trail> findByIdWithProject(@Param("id") Long id);

    
    
    @Query("SELECT t.id, t.title FROM Trail t WHERE t.id IN :ids")
    List<Object[]> findIdTitleByIdIn(@Param("ids") Collection<Long> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Trail t set t.version = t.version + 1 where t.project.id = :projectId")
    void bumpVersionsByProjectId(@Param("projectId") Long projectId);
}
