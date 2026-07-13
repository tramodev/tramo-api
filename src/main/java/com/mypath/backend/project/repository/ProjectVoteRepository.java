package com.mypath.backend.project.repository;

import com.mypath.backend.project.entity.ProjectVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectVoteRepository extends JpaRepository<ProjectVote, Long> {
    Optional<ProjectVote> findByProjectIdAndUserId(Long projectId, Long userId);
    long countByProjectId(Long projectId);
    List<ProjectVote> findByUserIdAndProjectIdIn(Long userId, List<Long> projectIds);
    List<ProjectVote> findByUserIdOrderByCreatedDateDesc(Long userId);
    void deleteByProjectId(Long projectId);

    @Query("SELECT COUNT(v) FROM ProjectVote v WHERE v.project.owner.id = :ownerId AND v.project.visibility = 'published'")
    long countByProjectOwnerIdAndProjectPublished(@Param("ownerId") Long ownerId);
}
