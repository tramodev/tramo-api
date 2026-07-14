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

    @Query("SELECT p FROM Project p JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.visibility = :visibility ORDER BY p.modifiedDate DESC")
    List<Project> findByVisibilityOrderByModifiedDateDesc(@Param("visibility") String visibility);
    long countByOwnerIdAndVisibility(Long ownerId, String visibility);
    long countByOwnerIdAndForkedFromNotNull(Long ownerId);
    List<Project> findByOwnerIdAndVisibilityOrderByCreationDateDesc(Long ownerId, String visibility);
    Optional<Project> findByFeaturedTrue();

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.owner.id = :ownerId AND p.forkedFrom IS NOT NULL ORDER BY p.creationDate DESC")
    List<Project> findByOwnerIdAndForkedFromNotNullOrderByCreationDateDesc(@Param("ownerId") Long ownerId);

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
