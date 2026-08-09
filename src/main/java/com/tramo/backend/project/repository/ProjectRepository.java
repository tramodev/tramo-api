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

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByOwnerId(Long ownerId);

    @Query("SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.visibility = :visibility ORDER BY p.lastPublishedDate DESC")
    List<Project> findByVisibilityOrderByLastPublishedDateDesc(@Param("visibility") ProjectVisibility visibility);

    String NOT_BLOCK_RELATED = "AND (:viewerId IS NULL OR NOT EXISTS ("
            + "SELECT 1 FROM BlockedUser b WHERE (b.blocker.id = :viewerId AND b.blocked.id = p.owner.id) "
            + "OR (b.blocker.id = p.owner.id AND b.blocked.id = :viewerId))) ";

    @Query(value = "SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner "
            + "WHERE p.visibility = :visibility AND p.owner.banned = false " + NOT_BLOCK_RELATED
            + "AND (:query = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR EXISTS (SELECT 1 FROM p.projectTags t WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', :query, '%')))) "
            + "ORDER BY p.lastPublishedDate DESC",
            countQuery = "SELECT COUNT(p) FROM Project p "
            + "WHERE p.visibility = :visibility AND p.owner.banned = false " + NOT_BLOCK_RELATED
            + "AND (:query = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR EXISTS (SELECT 1 FROM p.projectTags t WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', :query, '%'))))")
    Page<Project> findPublishedRecent(@Param("visibility") ProjectVisibility visibility, @Param("query") String query,
                                        @Param("viewerId") Long viewerId, Pageable pageable);

    @Query(value = "SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner "
            + "WHERE p.visibility = :visibility AND p.owner.banned = false AND p.owner.id IN :ownerIds " + NOT_BLOCK_RELATED
            + "AND (:query = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR EXISTS (SELECT 1 FROM p.projectTags t WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', :query, '%')))) "
            + "ORDER BY p.lastPublishedDate DESC",
            countQuery = "SELECT COUNT(p) FROM Project p "
            + "WHERE p.visibility = :visibility AND p.owner.banned = false AND p.owner.id IN :ownerIds " + NOT_BLOCK_RELATED
            + "AND (:query = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR EXISTS (SELECT 1 FROM p.projectTags t WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', :query, '%'))))")
    Page<Project> findPublishedRecentByOwners(@Param("visibility") ProjectVisibility visibility, @Param("ownerIds") List<Long> ownerIds,
                                                @Param("query") String query, @Param("viewerId") Long viewerId, Pageable pageable);

    @Query(value = "SELECT p.id FROM Project p LEFT JOIN ProjectVote v ON v.project = p "
            + "WHERE p.visibility = :visibility AND p.owner.banned = false " + NOT_BLOCK_RELATED
            + "AND (:query = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR EXISTS (SELECT 1 FROM p.projectTags t WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', :query, '%')))) "
            + "GROUP BY p.id ORDER BY COUNT(v) DESC, MAX(p.lastPublishedDate) DESC",
            countQuery = "SELECT COUNT(p) FROM Project p "
            + "WHERE p.visibility = :visibility AND p.owner.banned = false " + NOT_BLOCK_RELATED
            + "AND (:query = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR EXISTS (SELECT 1 FROM p.projectTags t WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', :query, '%'))))")
    Page<Long> findPublishedHotIds(@Param("visibility") ProjectVisibility visibility, @Param("query") String query,
                                     @Param("viewerId") Long viewerId, Pageable pageable);

    @Query("SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.id IN :ids")
    List<Project> findAllByIdInWithFetch(@Param("ids") List<Long> ids);

    long countByOwnerIdAndVisibility(Long ownerId, ProjectVisibility visibility);
    long countByOwnerIdAndForkedFromNotNull(Long ownerId);
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
