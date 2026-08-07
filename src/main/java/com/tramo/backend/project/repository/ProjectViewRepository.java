package com.tramo.backend.project.repository;

import com.tramo.backend.project.entity.ProjectView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface ProjectViewRepository extends JpaRepository<ProjectView, Long> {
    boolean existsByProjectIdAndViewerKey(Long projectId, String viewerKey);
    @Modifying(flushAutomatically = true)
    @Query("delete from ProjectView v where v.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
