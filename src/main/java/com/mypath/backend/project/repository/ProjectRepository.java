package com.mypath.backend.project.repository;

import com.mypath.backend.project.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByOwnerId(Long ownerId);
    List<Project> findByVisibilityOrderByModifiedDateDesc(String visibility);
    long countByOwnerIdAndVisibility(Long ownerId, String visibility);
    List<Project> findByOwnerIdAndForkedFromNotNullOrderByCreationDateDesc(Long ownerId);

    @Modifying
    @Query("UPDATE Project p SET p.viewCount = p.viewCount + 1 WHERE p.id = :id")
    void incrementViewCount(@Param("id") Long id);

    @Query("SELECT COALESCE(SUM(p.viewCount), 0) FROM Project p WHERE p.owner.id = :ownerId AND p.visibility = 'published'")
    long sumViewCountByOwnerIdAndPublished(@Param("ownerId") Long ownerId);

    @Modifying
    @Query("UPDATE Project p SET p.forkedFrom = null WHERE p.forkedFrom.id = :id")
    void clearForkedFromReferences(@Param("id") Long id);
}
