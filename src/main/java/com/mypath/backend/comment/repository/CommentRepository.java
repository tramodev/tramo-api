package com.mypath.backend.comment.repository;

import com.mypath.backend.comment.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {
    @Query("SELECT c FROM Comment c LEFT JOIN FETCH c.author LEFT JOIN FETCH c.parent WHERE c.project.id = :projectId ORDER BY c.createdDate ASC")
    List<Comment> findByProjectIdOrderByCreatedDateAsc(@Param("projectId") Long projectId);

    @Query("SELECT c.id FROM Comment c WHERE c.project.id = :projectId")
    List<Long> findIdsByProjectId(@Param("projectId") Long projectId);

    @Query("SELECT c.project.id AS projectId, COUNT(c) AS commentCount FROM Comment c WHERE c.project.id IN :projectIds AND c.deleted = false GROUP BY c.project.id")
    List<ProjectCommentCount> countGroupedByProjectIdIn(@Param("projectIds") List<Long> projectIds);

    interface ProjectCommentCount {
        Long getProjectId();
        Long getCommentCount();
    }

    @Modifying
    @Query("UPDATE Comment c SET c.parent = null WHERE c.project.id = :projectId")
    void clearParentReferencesForProject(@Param("projectId") Long projectId);

    void deleteByProjectId(Long projectId);

    @Modifying
    @Query("UPDATE Comment c SET c.author = null, c.content = null, c.deleted = true WHERE c.author.id = :authorId")
    void softDeleteByAuthorId(@Param("authorId") Long authorId);
}
