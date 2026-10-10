// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
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
    @Query("SELECT i FROM Item i WHERE i.project.id = :projectId ORDER BY i.id")
    List<Item> findEditorItems(@Param("projectId") Long projectId);

    @Query("SELECT i FROM Item i LEFT JOIN FETCH i.content WHERE i.id = :id")
    Optional<Item> findByIdWithContent(@Param("id") Long id);
    @Query(value = "SELECT id FROM item WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Long> lockById(@Param("id") Long id);

    
    
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

    @Query("SELECT i FROM Item i LEFT JOIN FETCH i.content WHERE i.project.id = :projectId OR EXISTS " +
            "(SELECT ti.id FROM TrailItem ti WHERE ti.item = i AND ti.trail.project.id = :projectId) ORDER BY i.id")
    List<Item> findForExport(@Param("projectId") Long projectId);

    @Query(value = "SELECT count(*) AS items, COALESCE(sum(octet_length(c.content)),0) AS bytes FROM item i LEFT JOIN item_content c ON c.id = i.content_id " +
            "WHERE i.project_id = :projectId OR EXISTS (SELECT 1 FROM trail_item ti JOIN trail t ON t.id = ti.trail_id WHERE ti.item_id = i.id AND t.project_id = :projectId)", nativeQuery = true)
    ExportSize exportSize(@Param("projectId") Long projectId);

    interface ExportSize { long getItems(); long getBytes(); }

    interface ProjectContentBytesSum {
        Long getProjectId();
        Long getBytes();
    }
}
