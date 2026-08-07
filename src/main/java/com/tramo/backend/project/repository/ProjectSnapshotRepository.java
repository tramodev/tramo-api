package com.tramo.backend.project.repository;

import com.tramo.backend.project.entity.ProjectSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;

@Repository
public interface ProjectSnapshotRepository extends JpaRepository<ProjectSnapshot, Long> {
    @Modifying(flushAutomatically = true)
    @Query("delete from ProjectSnapshot s where s.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);

    @Query("select max(s.version) from ProjectSnapshot s where s.project.id = :projectId and s.trigger = 'PUBLISH'")
    Optional<Integer> findMaxVersion(@Param("projectId") Long projectId);

    List<ProjectSnapshot> findByProjectIdAndTriggerOrderByVersionDesc(Long projectId, String trigger);

    
    
    
    @Query("SELECT s FROM ProjectSnapshot s JOIN FETCH s.project WHERE s.trigger = 'PUBLISH' AND s.id IN " +
            "(SELECT MAX(s2.id) FROM ProjectSnapshot s2 WHERE s2.trigger = 'PUBLISH' AND s2.project.id IN :projectIds GROUP BY s2.project.id)")
    List<ProjectSnapshot> findLatestPublishByProjectIdIn(@Param("projectIds") List<Long> projectIds);

    @Query("SELECT DISTINCT s.project.id FROM ProjectSnapshot s WHERE s.trigger = 'PUBLISH'")
    List<Long> findProjectIdsWithPublishSnapshot();
}
