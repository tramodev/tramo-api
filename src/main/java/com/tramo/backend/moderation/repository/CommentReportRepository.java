package com.tramo.backend.moderation.repository;

import com.tramo.backend.moderation.entity.CommentReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommentReportRepository extends JpaRepository<CommentReport, Long> {
    List<CommentReport> findByStatusOrderByCreatedDateDesc(String status);

    // Scalar projection for the admin list — see ProjectReportRepository.findOpenRows.
    // Columns: reportId, projectId, projectTitle, commentId, commentContent, reporterUsername, reason, status, createdDate.
    @Query("SELECT r.id, p.id, p.title, c.id, c.content, rep.username, r.reason, r.status, r.createdDate " +
            "FROM CommentReport r JOIN r.comment c JOIN c.project p JOIN r.reporter rep " +
            "WHERE r.status = :status ORDER BY r.createdDate DESC")
    List<Object[]> findOpenRows(@Param("status") String status);

    boolean existsByCommentIdAndReporterIdAndStatus(Long commentId, Long reporterId, String status);

    void deleteByCommentId(Long commentId);
    void deleteByCommentIdIn(java.util.List<Long> commentIds);
    void deleteByReporterId(Long reporterId);
}
