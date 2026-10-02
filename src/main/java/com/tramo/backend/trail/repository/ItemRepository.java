package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.dto.ItemTextStatsDTO;
import com.tramo.backend.trail.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ItemRepository extends JpaRepository<Item, Long> {
    
    
    @Query("SELECT i FROM Item i LEFT JOIN FETCH i.content WHERE i.project.id = :projectId")
    List<Item> findByProjectId(@Param("projectId") Long projectId);

    
    
    @Query("SELECT i FROM Item i LEFT JOIN FETCH i.project WHERE i.id = :id")
    Optional<Item> findByIdWithProject(@Param("id") Long id);

    
    
    @Query("SELECT i.id, i.title FROM Item i WHERE i.id IN :ids")
    List<Object[]> findIdTitleByIdIn(@Param("ids") Collection<Long> ids);

    
    @Query("SELECT COALESCE(SUM(function('octet_length', i.content.content)), 0) FROM Item i WHERE i.project.id = :projectId")
    long sumContentBytesByProjectId(@Param("projectId") Long projectId);

    @Query("SELECT i.project.id AS projectId, SUM(function('octet_length', i.content.content)) AS bytes FROM Item i WHERE i.project.id IN :projectIds GROUP BY i.project.id")
    List<ProjectContentBytesSum> sumContentBytesGroupedByProjectIdIn(@Param("projectIds") List<Long> projectIds);

    @Query("SELECT new com.tramo.backend.trail.dto.ItemTextStatsDTO(i.id, COALESCE(c.wordCount, 0L), COALESCE(c.characterCount, 0L)) " +
            "FROM Item i LEFT JOIN i.content c WHERE i.project.id = :projectId OR EXISTS " +
            "(SELECT ti.id FROM TrailItem ti WHERE ti.item = i AND ti.trail.project.id = :projectId)")
    List<ItemTextStatsDTO> findTextStatsByProjectId(@Param("projectId") Long projectId);

    interface ProjectContentBytesSum {
        Long getProjectId();
        Long getBytes();
    }
}
