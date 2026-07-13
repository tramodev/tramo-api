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
    List<ProjectBookmark> findByUserIdAndProjectIdIn(Long userId, List<Long> projectIds);

    // See the matching note in ProjectVoteRepository — avoids a per-row
    // secondary SELECT for project.owner.
    @Query("SELECT b FROM ProjectBookmark b LEFT JOIN FETCH b.project p LEFT JOIN FETCH p.owner WHERE b.user.id = :userId ORDER BY b.createdDate DESC")
    List<ProjectBookmark> findByUserIdOrderByCreatedDateDesc(@Param("userId") Long userId);

    List<ProjectBookmark> findByProjectOwnerIdAndUserIdNotOrderByCreatedDateDesc(Long ownerId, Long userId);
    void deleteByProjectId(Long projectId);
}
