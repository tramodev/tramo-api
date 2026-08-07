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
import org.springframework.data.jpa.repository.Modifying;

@Repository
public interface ProjectVoteRepository extends JpaRepository<ProjectVote, Long> {
    Optional<ProjectVote> findByProjectIdAndUserId(Long projectId, Long userId);
    long countByProjectId(Long projectId);
    @Query("SELECT v.project.id FROM ProjectVote v WHERE v.user.id = :userId AND v.project.id IN :projectIds")
    List<Long> findVotedProjectIds(@Param("userId") Long userId, @Param("projectIds") List<Long> projectIds);

    @Query("SELECT v FROM ProjectVote v LEFT JOIN FETCH v.project p LEFT JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE v.user.id = :userId ORDER BY v.createdDate DESC")
    List<ProjectVote> findByUserIdOrderByCreatedDateDesc(@Param("userId") Long userId, Pageable pageable);

    @Query(value = "SELECT v FROM ProjectVote v LEFT JOIN FETCH v.project p LEFT JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE v.user.id = :userId ORDER BY v.createdDate DESC",
            countQuery = "SELECT COUNT(v) FROM ProjectVote v WHERE v.user.id = :userId")
    Page<ProjectVote> findByUserIdOrderByCreatedDateDescPaged(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT v FROM ProjectVote v JOIN FETCH v.user LEFT JOIN FETCH v.project p LEFT JOIN FETCH p.owner LEFT JOIN FETCH p.forkedFrom fo LEFT JOIN FETCH fo.owner WHERE p.owner.id = :ownerId AND v.user.id <> :userId ORDER BY v.createdDate DESC")
    List<ProjectVote> findByProjectOwnerIdAndUserIdNotOrderByCreatedDateDesc(@Param("ownerId") Long ownerId, @Param("userId") Long userId, Pageable pageable);
    @Modifying(flushAutomatically = true)
    @Query("delete from ProjectVote v where v.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
    @Modifying(flushAutomatically = true)
    @Query("delete from ProjectVote v where v.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(v) FROM ProjectVote v WHERE v.project.owner.id = :ownerId AND v.project.visibility = 'published'")
    long countByProjectOwnerIdAndProjectPublished(@Param("ownerId") Long ownerId);

    @Query("SELECT v.project.id AS projectId, COUNT(v) AS voteCount FROM ProjectVote v WHERE v.project.id IN :projectIds GROUP BY v.project.id")
    List<ProjectVoteCount> countGroupedByProjectIdIn(@Param("projectIds") List<Long> projectIds);

    interface ProjectVoteCount {
        Long getProjectId();
        Long getVoteCount();
    }

    @Query(value = "SELECT t.project_id AS projectId, "
            + "COUNT(*) FILTER (WHERE (t.voter_ip IS NULL OR t.ip_rn = 1) AND (t.device_id IS NULL OR t.dev_rn = 1)) AS trustedCount, "
            + "COUNT(*) AS rawCount "
            + "FROM (SELECT v.project_id, v.voter_ip, v.device_id, p.last_published_date, "
            + "ROW_NUMBER() OVER (PARTITION BY v.project_id, v.voter_ip ORDER BY v.created_date, v.id) AS ip_rn, "
            + "ROW_NUMBER() OVER (PARTITION BY v.project_id, v.device_id ORDER BY v.created_date, v.id) AS dev_rn "
            + "FROM project_vote v JOIN project p ON p.id = v.project_id WHERE p.visibility = 'published') t "
            + "GROUP BY t.project_id "
            + "ORDER BY 2 DESC, MAX(t.last_published_date) DESC NULLS LAST "
            + "LIMIT 1", nativeQuery = true)
    Optional<TrustedVoteCount> findTopTrustedPublished();

    interface TrustedVoteCount {
        Long getProjectId();
        Long getTrustedCount();
        Long getRawCount();
    }
}
