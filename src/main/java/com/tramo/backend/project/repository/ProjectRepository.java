// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.repository;

import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByOwnerId(Long ownerId);

    @Query("SELECT p FROM Project p WHERE p.owner.id = :ownerId AND p.example = true")
    Optional<Project> findExampleByOwnerId(@Param("ownerId") Long ownerId);
    Optional<Project> findByOwnerIdAndStartRequestId(Long ownerId, UUID startRequestId);
    Optional<Project> findFirstByOwnerIdAndForkedFromIdAndCreationDateAfterOrderByIdDesc(Long ownerId, Long sourceId, java.util.Date since);

    @Query("SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.visibility = :visibility ORDER BY p.lastPublishedDate DESC")
    List<Project> findByVisibilityOrderByLastPublishedDateDesc(@Param("visibility") ProjectVisibility visibility);

    String NOT_BLOCK_RELATED = "AND (:viewerId IS NULL OR NOT EXISTS ("
            + "SELECT 1 FROM BlockedUser b WHERE (b.blocker.id = :viewerId AND b.blocked.id = p.owner.id) "
            + "OR (b.blocker.id = p.owner.id AND b.blocked.id = :viewerId))) ";

    String PUBLISHED_SEARCH = "AND (:query = '' OR EXISTS (SELECT 1 FROM ProjectSnapshot s "
            + "WHERE s.project = p AND s.trigger = 'PUBLISH' AND s.id = "
            + "(SELECT MAX(s2.id) FROM ProjectSnapshot s2 WHERE s2.project = p AND s2.trigger = 'PUBLISH') "
            + "AND (LOWER(CAST(sql('json_extract_path_text(cast(? as json), ''title'')', s.content) AS String)) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '!' "
            + "OR LOWER(CAST(sql('json_extract_path_text(cast(? as json), ''description'')', s.content) AS String)) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '!' "
            + "OR LOWER(CAST(sql('json_extract_path_text(cast(? as json), ''tags'')', s.content) AS String)) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '!'))) ";

    @Query("SELECT p.id AS id, COUNT(v) AS voteCount, p.lastPublishedDate AS publishedAt "
            + "FROM Project p LEFT JOIN ProjectVote v ON v.project = p "
            + "WHERE p.visibility = :visibility AND p.owner.banned = false " + NOT_BLOCK_RELATED + PUBLISHED_SEARCH
            + "AND (:following = false OR p.owner.id IN :ownerIds) GROUP BY p.id, p.lastPublishedDate "
            + "HAVING :beforeId IS NULL OR (:hot = true AND COUNT(v) < :beforeVotes) "
            + "OR ((:hot = false OR COUNT(v) = :beforeVotes) AND "
            + "((CAST(:beforeDate AS timestamp) IS NULL AND (p.lastPublishedDate IS NOT NULL OR p.id < :beforeId)) "
            + "OR (CAST(:beforeDate AS timestamp) IS NOT NULL AND (p.lastPublishedDate < :beforeDate "
            + "OR (p.lastPublishedDate = :beforeDate AND p.id < :beforeId))))) "
            + "ORDER BY CASE WHEN :hot = true THEN COUNT(v) ELSE 0 END DESC, p.lastPublishedDate DESC, p.id DESC")
    org.springframework.data.domain.Slice<ExploreRow> findExplorePage(ProjectVisibility visibility, String query, Long viewerId,
            boolean following, List<Long> ownerIds, boolean hot, Long beforeId, java.util.Date beforeDate,
            long beforeVotes, Pageable pageable);

    interface ExploreRow {
        Long getId();
        long getVoteCount();
        java.util.Date getPublishedAt();
    }

    @Query(value = "SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner "
            + "WHERE p.visibility = :visibility AND p.owner.banned = false " + NOT_BLOCK_RELATED
            + PUBLISHED_SEARCH
            + "ORDER BY p.lastPublishedDate DESC, p.id DESC",
            countQuery = "SELECT COUNT(p) FROM Project p "
            + "WHERE p.visibility = :visibility AND p.owner.banned = false " + NOT_BLOCK_RELATED
            + PUBLISHED_SEARCH)
    Page<Project> findPublishedRecent(@Param("visibility") ProjectVisibility visibility, @Param("query") String query,
                                        @Param("viewerId") Long viewerId, Pageable pageable);

    @Query(value = "SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner "
            + "WHERE p.visibility = :visibility AND p.owner.banned = false AND p.owner.id IN :ownerIds " + NOT_BLOCK_RELATED
            + PUBLISHED_SEARCH
            + "ORDER BY p.lastPublishedDate DESC, p.id DESC",
            countQuery = "SELECT COUNT(p) FROM Project p "
            + "WHERE p.visibility = :visibility AND p.owner.banned = false AND p.owner.id IN :ownerIds " + NOT_BLOCK_RELATED
            + PUBLISHED_SEARCH)
    Page<Project> findPublishedRecentByOwners(@Param("visibility") ProjectVisibility visibility, @Param("ownerIds") List<Long> ownerIds,
                                                @Param("query") String query, @Param("viewerId") Long viewerId, Pageable pageable);

    @Query(value = "SELECT p.id FROM Project p LEFT JOIN ProjectVote v ON v.project = p "
            + "WHERE p.visibility = :visibility AND p.owner.banned = false " + NOT_BLOCK_RELATED
            + PUBLISHED_SEARCH
            + "GROUP BY p.id ORDER BY COUNT(v) DESC, MAX(p.lastPublishedDate) DESC, p.id DESC",
            countQuery = "SELECT COUNT(p) FROM Project p "
            + "WHERE p.visibility = :visibility AND p.owner.banned = false " + NOT_BLOCK_RELATED
            + PUBLISHED_SEARCH)
    Page<Long> findPublishedHotIds(@Param("visibility") ProjectVisibility visibility, @Param("query") String query,
                                     @Param("viewerId") Long viewerId, Pageable pageable);

    @Query("SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.id IN :ids")
    List<Project> findAllByIdInWithFetch(@Param("ids") List<Long> ids);

    long countByOwnerIdAndVisibility(Long ownerId, ProjectVisibility visibility);
    long countByForkedFromOwnerIdAndOwnerIdNot(Long forkedFromOwnerId, Long ownerId);

    @Query("SELECT COUNT(DISTINCT p.owner.id) FROM Project p WHERE p.forkedFrom.owner.id = :ownerId AND p.owner.id <> :ownerId")
    long countDistinctForkersByOwnerId(@Param("ownerId") Long ownerId);
    boolean existsByIdAndOwnerId(Long id, Long ownerId);
    @Query("SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.owner.id = :ownerId AND p.visibility = :visibility ORDER BY p.creationDate DESC")
    List<Project> findByOwnerIdAndVisibilityOrderByCreationDateDesc(@Param("ownerId") Long ownerId, @Param("visibility") ProjectVisibility visibility, Pageable pageable);

    @Query(value = "SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.owner.id = :ownerId AND p.visibility = :visibility ORDER BY p.creationDate DESC",
            countQuery = "SELECT COUNT(p) FROM Project p WHERE p.owner.id = :ownerId AND p.visibility = :visibility")
    Page<Project> findByOwnerIdAndVisibilityOrderByCreationDateDescPaged(@Param("ownerId") Long ownerId, @Param("visibility") ProjectVisibility visibility, Pageable pageable);

    Optional<Project> findByFeaturedTrue();

    Optional<Project> findFirstByVisibilityOrderByLastPublishedDateDesc(ProjectVisibility visibility);

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.owner.id = :ownerId AND p.forkedFrom IS NOT NULL ORDER BY p.creationDate DESC")
    List<Project> findByOwnerIdAndForkedFromNotNullOrderByCreationDateDesc(@Param("ownerId") Long ownerId, Pageable pageable);

    @Query(value = "SELECT p FROM Project p LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.owner.id = :ownerId AND p.forkedFrom IS NOT NULL ORDER BY p.creationDate DESC",
            countQuery = "SELECT COUNT(p) FROM Project p WHERE p.owner.id = :ownerId AND p.forkedFrom IS NOT NULL")
    Page<Project> findByOwnerIdAndForkedFromNotNullOrderByCreationDateDescPaged(@Param("ownerId") Long ownerId, Pageable pageable);

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH p.owner WHERE fo.owner.id = :forkedFromOwnerId AND p.owner.id <> :ownerId ORDER BY p.creationDate DESC")
    List<Project> findByForkedFromOwnerIdAndOwnerIdNotOrderByCreationDateDesc(@Param("forkedFromOwnerId") Long forkedFromOwnerId, @Param("ownerId") Long ownerId, Pageable pageable);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Project p SET p.lastEditedDate = :now WHERE p.id = :id "
            + "AND (p.lastEditedDate IS NULL OR p.lastEditedDate < :staleBefore)")
    void touchLastEditedDate(@Param("id") Long id, @Param("now") java.util.Date now, @Param("staleBefore") java.util.Date staleBefore);

    @Modifying
    @Query("UPDATE Project p SET p.viewCount = p.viewCount + 1 WHERE p.id = :id")
    void incrementViewCount(@Param("id") Long id);

    @Query("SELECT COALESCE(SUM(p.viewCount), 0) FROM Project p WHERE p.owner.id = :ownerId AND p.visibility = 'published'")
    long sumViewCountByOwnerIdAndPublished(@Param("ownerId") Long ownerId);

    @Modifying
    @Query("UPDATE Project p SET p.forkedFrom = null WHERE p.forkedFrom.id = :id")
    void clearForkedFromReferences(@Param("id") Long id);

    @Query("SELECT p.forkedFrom.id AS projectId, COUNT(p) AS forkCount FROM Project p WHERE p.forkedFrom.id IN :projectIds GROUP BY p.forkedFrom.id")
    List<ProjectForkCount> countGroupedByForkedFromIdIn(@Param("projectIds") List<Long> projectIds);

    interface ProjectForkCount {
        Long getProjectId();
        Long getForkCount();
    }

    
    
    @Query("SELECT p.id AS projectId, t.name AS tagName FROM Project p JOIN p.projectTags t WHERE p.id IN :projectIds")
    List<ProjectTagName> findTagNamesGroupedByProjectIdIn(@Param("projectIds") List<Long> projectIds);

    interface ProjectTagName {
        Long getProjectId();
        String getTagName();
    }

    @Query("SELECT p.owner.username AS username, p.owner.imageUrl AS avatar, COUNT(p) AS count FROM Project p "
            + "WHERE p.visibility = :visibility AND p.owner.banned = false GROUP BY p.owner.username, p.owner.imageUrl ORDER BY COUNT(p) DESC")
    List<AuthorCount> findActiveAuthors(@Param("visibility") ProjectVisibility visibility, Pageable pageable);

    interface AuthorCount {
        String getUsername();
        String getAvatar();
        Long getCount();
    }
}
