package com.tramo.backend.moderation.repository;

import com.tramo.backend.moderation.entity.CommentReport;
import com.tramo.backend.moderation.entity.ReportStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

import org.springframework.data.jpa.repository.Modifying;

public interface CommentReportRepository extends JpaRepository<CommentReport, Long> {

    @Query("SELECT r.id, p.id, p.title, c.id, c.content, rep.username, r.reason, r.status, r.createdDate " +
            "FROM CommentReport r JOIN r.comment c JOIN c.project p JOIN r.reporter rep " +
            "WHERE r.status = :status ORDER BY r.createdDate DESC")
    List<Object[]> findOpenRows(@Param("status") ReportStatus status, Pageable pageable);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE CommentReport r SET r.status = :to WHERE r.comment.id = :commentId AND r.status = :from")
    int updateStatusByCommentId(@Param("commentId") Long commentId, @Param("from") ReportStatus from, @Param("to") ReportStatus to);

    boolean existsByCommentIdAndReporterIdAndStatus(Long commentId, Long reporterId, ReportStatus status);

    @Modifying(flushAutomatically = true)
    @Query("delete from CommentReport r where r.comment.id = :commentId")
    void deleteByCommentId(@Param("commentId") Long commentId);
    @Modifying(flushAutomatically = true)
    @Query("delete from CommentReport r where r.comment.id in :commentIds")
    void deleteByCommentIdIn(@Param("commentIds") java.util.List<Long> commentIds);
    @Modifying(flushAutomatically = true)
    @Query("delete from CommentReport r where r.reporter.id = :reporterId")
    void deleteByReporterId(@Param("reporterId") Long reporterId);
}
