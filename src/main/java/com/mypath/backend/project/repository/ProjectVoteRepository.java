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
    // JOIN FETCH here so reading vote.getProject().getOwner() downstream (the
    // project's owner is a genuinely different user, not shortcut-able) hits
    // one query instead of Hibernate resolving that eager association with a
    // separate SELECT per row.
    @Query("SELECT v FROM ProjectVote v LEFT JOIN FETCH v.project p LEFT JOIN FETCH p.owner WHERE v.user.id = :userId ORDER BY v.createdDate DESC")
    List<ProjectVote> findByUserIdOrderByCreatedDateDesc(@Param("userId") Long userId);

    List<ProjectVote> findByProjectOwnerIdAndUserIdNotOrderByCreatedDateDesc(Long ownerId, Long userId);
    void deleteByProjectId(Long projectId);

    @Query("SELECT COUNT(v) FROM ProjectVote v WHERE v.project.owner.id = :ownerId AND v.project.visibility = 'published'")
    long countByProjectOwnerIdAndProjectPublished(@Param("ownerId") Long ownerId);

    // Grouped count in one round trip instead of countByProjectId called once
    // per row — that per-row version is what was turning a 20-item feed list
    // into 20+ extra queries.
    @Query("SELECT v.project.id AS projectId, COUNT(v) AS voteCount FROM ProjectVote v WHERE v.project.id IN :projectIds GROUP BY v.project.id")
    List<ProjectVoteCount> countGroupedByProjectIdIn(@Param("projectIds") List<Long> projectIds);

    interface ProjectVoteCount {
        Long getProjectId();
        Long getVoteCount();
    }
}
