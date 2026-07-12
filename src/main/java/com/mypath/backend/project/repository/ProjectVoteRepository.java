package com.mypath.backend.project.repository;

import com.mypath.backend.project.entity.ProjectVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectVoteRepository extends JpaRepository<ProjectVote, Long> {
    Optional<ProjectVote> findByProjectIdAndUserId(Long projectId, Long userId);
    long countByProjectId(Long projectId);
    List<ProjectVote> findByUserIdAndProjectIdIn(Long userId, List<Long> projectIds);
    void deleteByProjectId(Long projectId);
}
