package com.mypath.backend.project.repository;

import com.mypath.backend.project.entity.ProjectBookmark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectBookmarkRepository extends JpaRepository<ProjectBookmark, Long> {
    Optional<ProjectBookmark> findByProjectIdAndUserId(Long projectId, Long userId);
    @Query("SELECT b.project.id FROM ProjectBookmark b WHERE b.user.id = :userId AND b.project.id IN :projectIds")
    List<Long> findBookmarkedProjectIds(@Param("userId") Long userId, @Param("projectIds") List<Long> projectIds);

    @Query("SELECT b FROM ProjectBookmark b LEFT JOIN FETCH b.project p LEFT JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE b.user.id = :userId ORDER BY b.createdDate DESC")
    List<ProjectBookmark> findByUserIdOrderByCreatedDateDesc(@Param("userId") Long userId);

    @Query("SELECT b FROM ProjectBookmark b JOIN FETCH b.user LEFT JOIN FETCH b.project p LEFT JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.owner.id = :ownerId AND b.user.id <> :userId ORDER BY b.createdDate DESC")
    List<ProjectBookmark> findByProjectOwnerIdAndUserIdNotOrderByCreatedDateDesc(@Param("ownerId") Long ownerId, @Param("userId") Long userId);
    void deleteByProjectId(Long projectId);
}
