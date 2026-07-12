package com.mypath.backend.project.repository;

import com.mypath.backend.project.entity.ProjectBookmark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectBookmarkRepository extends JpaRepository<ProjectBookmark, Long> {
    Optional<ProjectBookmark> findByProjectIdAndUserId(Long projectId, Long userId);
    List<ProjectBookmark> findByUserIdAndProjectIdIn(Long userId, List<Long> projectIds);
    void deleteByProjectId(Long projectId);
}
