package com.mypath.backend.project.repository;

import com.mypath.backend.project.entity.Project;
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

    @Query("SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.visibility = :visibility ORDER BY p.modifiedDate DESC")
    List<Project> findByVisibilityOrderByModifiedDateDesc(@Param("visibility") String visibility);

    @Query(value = "SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner "
            + "WHERE p.visibility = :visibility AND (:query = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(p.tags) LIKE LOWER(CONCAT('%', :query, '%'))) "
            + "ORDER BY p.modifiedDate DESC",
            countQuery = "SELECT COUNT(p) FROM Project p "
            + "WHERE p.visibility = :visibility AND (:query = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(p.tags) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<Project> findPublishedRecent(@Param("visibility") String visibility, @Param("query") String query, Pageable pageable);

    @Query(value = "SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner "
            + "WHERE p.visibility = :visibility AND p.owner.id IN :ownerIds AND (:query = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(p.tags) LIKE LOWER(CONCAT('%', :query, '%'))) "
            + "ORDER BY p.modifiedDate DESC",
            countQuery = "SELECT COUNT(p) FROM Project p "
            + "WHERE p.visibility = :visibility AND p.owner.id IN :ownerIds AND (:query = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(p.tags) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<Project> findPublishedRecentByOwners(@Param("visibility") String visibility, @Param("ownerIds") List<Long> ownerIds,
                                                @Param("query") String query, Pageable pageable);

    @Query(value = "SELECT p.id FROM Project p LEFT JOIN ProjectVote v ON v.project = p "
            + "WHERE p.visibility = :visibility AND (:query = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(p.tags) LIKE LOWER(CONCAT('%', :query, '%'))) "
            + "GROUP BY p.id ORDER BY COUNT(v) DESC, MAX(p.modifiedDate) DESC",
            countQuery = "SELECT COUNT(p) FROM Project p "
            + "WHERE p.visibility = :visibility AND (:query = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(p.tags) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<Long> findPublishedHotIds(@Param("visibility") String visibility, @Param("query") String query, Pageable pageable);

    @Query("SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.id IN :ids")
    List<Project> findAllByIdInWithFetch(@Param("ids") List<Long> ids);

    long countByOwnerIdAndVisibility(Long ownerId, String visibility);
    long countByOwnerIdAndForkedFromNotNull(Long ownerId);
    @Query("SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.owner.id = :ownerId AND p.visibility = :visibility ORDER BY p.creationDate DESC")
    List<Project> findByOwnerIdAndVisibilityOrderByCreationDateDesc(@Param("ownerId") Long ownerId, @Param("visibility") String visibility);

    @Query(value = "SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.owner.id = :ownerId AND p.visibility = :visibility ORDER BY p.creationDate DESC",
            countQuery = "SELECT COUNT(p) FROM Project p WHERE p.owner.id = :ownerId AND p.visibility = :visibility")
    Page<Project> findByOwnerIdAndVisibilityOrderByCreationDateDescPaged(@Param("ownerId") Long ownerId, @Param("visibility") String visibility, Pageable pageable);

    Optional<Project> findByFeaturedTrue();

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.owner.id = :ownerId AND p.forkedFrom IS NOT NULL ORDER BY p.creationDate DESC")
    List<Project> findByOwnerIdAndForkedFromNotNullOrderByCreationDateDesc(@Param("ownerId") Long ownerId);

    @Query(value = "SELECT p FROM Project p LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.owner.id = :ownerId AND p.forkedFrom IS NOT NULL ORDER BY p.creationDate DESC",
            countQuery = "SELECT COUNT(p) FROM Project p WHERE p.owner.id = :ownerId AND p.forkedFrom IS NOT NULL")
    Page<Project> findByOwnerIdAndForkedFromNotNullOrderByCreationDateDescPaged(@Param("ownerId") Long ownerId, Pageable pageable);

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

    @Query("SELECT p.forkedFrom.id AS projectId, COUNT(p) AS forkCount FROM Project p WHERE p.forkedFrom.id IN :projectIds GROUP BY p.forkedFrom.id")
    List<ProjectForkCount> countGroupedByForkedFromIdIn(@Param("projectIds") List<Long> projectIds);

    interface ProjectForkCount {
        Long getProjectId();
        Long getForkCount();
    }
}
