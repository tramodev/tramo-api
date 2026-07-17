package com.mypath.backend.moderation.repository;

import com.mypath.backend.moderation.entity.CommentReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentReportRepository extends JpaRepository<CommentReport, Long> {
    List<CommentReport> findByStatusOrderByCreatedDateDesc(String status);

    boolean existsByCommentIdAndReporterIdAndStatus(Long commentId, Long reporterId, String status);

    void deleteByCommentId(Long commentId);
    void deleteByCommentIdIn(java.util.List<Long> commentIds);
    void deleteByReporterId(Long reporterId);
}
