package com.tramo.backend.project.repository;

import com.tramo.backend.project.entity.ProjectVote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    @Query("SELECT v.project.id FROM ProjectVote v WHERE v.user.id = :userId AND v.project.id IN :projectIds")
    List<Long> findVotedProjectIds(@Param("userId") Long userId, @Param("projectIds") List<Long> projectIds);

    @Query("SELECT v FROM ProjectVote v LEFT JOIN FETCH v.project p LEFT JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE v.user.id = :userId ORDER BY v.createdDate DESC")
    List<ProjectVote> findByUserIdOrderByCreatedDateDesc(@Param("userId") Long userId);

    @Query(value = "SELECT v FROM ProjectVote v LEFT JOIN FETCH v.project p LEFT JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE v.user.id = :userId ORDER BY v.createdDate DESC",
            countQuery = "SELECT COUNT(v) FROM ProjectVote v WHERE v.user.id = :userId")
    Page<ProjectVote> findByUserIdOrderByCreatedDateDescPaged(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT v FROM ProjectVote v JOIN FETCH v.user LEFT JOIN FETCH v.project p LEFT JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.owner.id = :ownerId AND v.user.id <> :userId ORDER BY v.createdDate DESC")
    List<ProjectVote> findByProjectOwnerIdAndUserIdNotOrderByCreatedDateDesc(@Param("ownerId") Long ownerId, @Param("userId") Long userId);
    void deleteByProjectId(Long projectId);
    void deleteByUserId(Long userId);

    @Query("SELECT COUNT(v) FROM ProjectVote v WHERE v.project.owner.id = :ownerId AND v.project.visibility = 'published'")
    long countByProjectOwnerIdAndProjectPublished(@Param("ownerId") Long ownerId);

    @Query("SELECT v.project.id AS projectId, COUNT(v) AS voteCount FROM ProjectVote v WHERE v.project.id IN :projectIds GROUP BY v.project.id")
    List<ProjectVoteCount> countGroupedByProjectIdIn(@Param("projectIds") List<Long> projectIds);

    interface ProjectVoteCount {
        Long getProjectId();
        Long getVoteCount();
    }

    @Query("SELECT v.project.id AS projectId, v.voterIp AS voterIp, v.deviceId AS deviceId FROM ProjectVote v WHERE v.project.id IN :projectIds ORDER BY v.createdDate ASC")
    List<VoteMeta> findMetaByProjectIdIn(@Param("projectIds") List<Long> projectIds);

    interface VoteMeta {
        Long getProjectId();
        String getVoterIp();
        String getDeviceId();
    }
}
