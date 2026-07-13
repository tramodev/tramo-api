package com.mypath.backend.project.repository;

import com.mypath.backend.project.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByOwnerId(Long ownerId);
    List<Project> findByVisibilityOrderByModifiedDateDesc(String visibility);
    long countByOwnerIdAndVisibility(Long ownerId, String visibility);
    long countByOwnerIdAndForkedFromNotNull(Long ownerId);
    List<Project> findByOwnerIdAndVisibilityOrderByCreationDateDesc(Long ownerId, String visibility);
    Optional<Project> findByFeaturedTrue();

    // forkedFrom/owner are plain (non-lazy) @ManyToOne, so without an explicit
    // JOIN FETCH here Hibernate resolves each row's forkedFrom (and its own
    // owner) with a separate SELECT per row after the fact — confirmed via
    // query logs firing N extra "where p.id = ?" selects for an N-row result.
    // Callers of this one only ever read forkedFrom's fields, never p.owner
    // (that's always the same `ownerId` already in hand), so only fo/fo.owner
    // need fetching.
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.owner.id = :ownerId AND p.forkedFrom IS NOT NULL ORDER BY p.creationDate DESC")
    List<Project> findByOwnerIdAndForkedFromNotNullOrderByCreationDateDesc(@Param("ownerId") Long ownerId);

    // Here p.owner (the forker) is a genuinely different user and is read by
    // callers, so it's fetched too; fo (the source project, always the
    // caller's own) only needs its own columns, not fo.owner.
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH p.owner WHERE fo.owner.id = :forkedFromOwnerId AND p.owner.id <> :ownerId ORDER BY p.creationDate DESC")
    List<Project> findByForkedFromOwnerIdAndOwnerIdNotOrderByCreationDateDesc(@Param("forkedFromOwnerId") Long forkedFromOwnerId, @Param("ownerId") Long ownerId);

    @Modifying
    @Query("UPDATE Project p SET p.viewCount = p.viewCount + 1 WHERE p.id = :id")
    void incrementViewCount(@Param("id") Long id);

    @Query("SELECT COALESCE(SUM(p.viewCount), 0) FROM Project p WHERE p.owner.id = :ownerId AND p.visibility = 'published'")
    long sumViewCountByOwnerIdAndPublished(@Param("ownerId") Long ownerId);

    @Modifying
    @Query("UPDATE Project p SET p.forkedFrom = null WHERE p.forkedFrom.id = :id")
    void clearForkedFromReferences(@Param("id") Long id);

    // Grouped per-project fork count for feed cards — same batching pattern as
    // ProjectVoteRepository.countGroupedByProjectIdIn, avoids a query per row.
    @Query("SELECT p.forkedFrom.id AS projectId, COUNT(p) AS forkCount FROM Project p WHERE p.forkedFrom.id IN :projectIds GROUP BY p.forkedFrom.id")
    List<ProjectForkCount> countGroupedByForkedFromIdIn(@Param("projectIds") List<Long> projectIds);

    interface ProjectForkCount {
        Long getProjectId();
        Long getForkCount();
    }
}
