// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.moderation.service;

import com.tramo.backend.auth.repository.RefreshTokenRepository;
import com.tramo.backend.project.dto.PageResponseDTO;
import com.tramo.backend.moderation.dto.ModerationLogDTO;
import com.tramo.backend.common.Pagination;
import com.tramo.backend.exception.RequestValidationException;
import com.tramo.backend.exception.RequestErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import com.tramo.backend.user.Role;
import com.tramo.backend.auth.service.EmailService;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.comment.entity.Comment;
import com.tramo.backend.comment.repository.CommentRepository;
import com.tramo.backend.comment.service.CommentService;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.moderation.dto.AdminUserDTO;
import com.tramo.backend.moderation.dto.ReportDTO;
import com.tramo.backend.moderation.entity.CommentReport;
import com.tramo.backend.moderation.entity.ModerationLog;
import com.tramo.backend.moderation.entity.ProjectReport;
import com.tramo.backend.moderation.entity.ReportStatus;
import com.tramo.backend.moderation.repository.CommentReportRepository;
import com.tramo.backend.moderation.repository.ModerationLogRepository;
import com.tramo.backend.moderation.repository.ProjectReportRepository;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.service.AccessGuard;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Service
public class ModerationService {
    private static final int ADMIN_SEARCH_LIMIT = 50;
    private final ProjectReportRepository projectReportRepository;
    private final CommentReportRepository commentReportRepository;
    private final ModerationLogRepository moderationLogRepository;
    private final ProjectRepository projectRepository;
    private final CommentRepository commentRepository;
    private final CommentService commentService;
    private final UserRepository userRepository;
    private final ProjectIdCodec projectIdCodec;
    private final RefreshTokenRepository refreshTokenRepository;
    private final NotificationService notifications;
    private final AccessGuard accessGuard;
    private final EntityManager em;
    private final EmailService emailService;

    public ModerationService(ProjectReportRepository projectReportRepository,
                              CommentReportRepository commentReportRepository,
                              ModerationLogRepository moderationLogRepository,
                              ProjectRepository projectRepository,
                              CommentRepository commentRepository,
                              CommentService commentService,
                              UserRepository userRepository,
                              ProjectIdCodec projectIdCodec,
                              RefreshTokenRepository refreshTokenRepository, NotificationService notifications,
                              AccessGuard accessGuard, EntityManager em, EmailService emailService) {
        this.projectReportRepository = projectReportRepository;
        this.commentReportRepository = commentReportRepository;
        this.moderationLogRepository = moderationLogRepository;
        this.projectRepository = projectRepository;
        this.commentRepository = commentRepository;
        this.commentService = commentService;
        this.userRepository = userRepository;
        this.projectIdCodec = projectIdCodec;
        this.refreshTokenRepository = refreshTokenRepository;
        this.notifications = notifications;
        this.accessGuard = accessGuard;
        this.em = em;
        this.emailService = emailService;
    }

    @Transactional
    public void submitReport(Long projectId, User reporter, String reason) {
        validateReason(reason);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        if (project.getOwner().getId().equals(reporter.getId())) {
            throw new AccessDeniedException("Cannot report your own project");
        }
        accessGuard.assertViewable(project, reporter);
        if (projectReportRepository.existsByProjectIdAndReporterIdAndStatus(projectId, reporter.getId(), ReportStatus.OPEN)) {
            return;
        }

        ProjectReport report = new ProjectReport();
        report.setProject(project);
        report.setReporter(reporter);
        report.setReason(reason);
        report.setStatus(ReportStatus.OPEN);
        report.setCreatedDate(new Date());
        projectReportRepository.save(report);
    }

    @Transactional
    public void submitCommentReport(Long commentId, User reporter, String reason) {
        validateReason(reason);
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment not found"));

        if (comment.getAuthor() != null && comment.getAuthor().getId().equals(reporter.getId())) {
            throw new AccessDeniedException("Cannot report your own comment");
        }
        accessGuard.assertViewable(comment.getProject(), reporter);
        if (commentReportRepository.existsByCommentIdAndReporterIdAndStatus(commentId, reporter.getId(), ReportStatus.OPEN)) {
            return;
        }

        CommentReport report = new CommentReport();
        report.setComment(comment);
        report.setReporter(reporter);
        report.setReason(reason);
        report.setStatus(ReportStatus.OPEN);
        report.setCreatedDate(new Date());
        commentReportRepository.save(report);
    }

    public PageResponseDTO<ReportDTO> listReports(User admin, int page, int size, String type, ReportStatus status) {
        requireAdmin(admin);
        if (!List.of("ALL", "PROJECT", "COMMENT").contains(type)) throw new RequestValidationException(RequestErrorCode.INVALID_VALUE);
        var rows = projectReportRepository.findReportRows(type, status.name(), Pagination.page(page, size));
        var reports = rows.getContent().stream().map(r -> new ReportDTO(
                ((Number) r[0]).longValue(), (String) r[1], projectIdCodec.encode(((Number) r[2]).longValue()),
                (String) r[3], r[4] == null ? null : ((Number) r[4]).longValue(), (String) r[5],
                (String) r[6], (String) r[7], (String) r[8], Timestamp.valueOf((LocalDateTime) r[9]))).toList();
        return new PageResponseDTO<>(reports, rows.hasNext());
    }

    public PageResponseDTO<ModerationLogDTO> listLog(User admin, int page, int size) {
        requireAdmin(admin);
        var rows = moderationLogRepository.findRows(Pagination.page(page, size));
        var entries = rows.getContent().stream().map(r -> new ModerationLogDTO(
                (Long) r[0], (String) r[1], (String) r[2], (String) r[3],
                "PROJECT".equals(r[3]) ? projectIdCodec.encode((Long) r[4]) : String.valueOf(r[4]),
                (String) r[5], (Date) r[6], (Boolean) r[7])).toList();
        return new PageResponseDTO<>(entries, rows.hasNext());
    }

    @Transactional
    public void dismissReport(Long reportId, String type, User admin) {
        requireAdmin(admin);
        if ("COMMENT".equals(type)) {
            CommentReport report = commentReportRepository.findById(reportId)
                    .orElseThrow(() -> new ResourceNotFoundException("Report not found"));
            em.refresh(report.getComment(), LockModeType.PESSIMISTIC_WRITE);
            em.refresh(report);
            if (report.getStatus() != ReportStatus.OPEN) return;
            report.setStatus(ReportStatus.DISMISSED);
            logAction(admin, "DISMISS_REPORT", "COMMENT_REPORT", reportId, null);
            return;
        }
        if (!"PROJECT".equals(type)) throw new RequestValidationException(RequestErrorCode.INVALID_VALUE);
        ProjectReport report = projectReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found"));
        em.refresh(report.getProject(), LockModeType.PESSIMISTIC_WRITE);
        em.refresh(report);
        if (report.getStatus() != ReportStatus.OPEN) return;
        report.setStatus(ReportStatus.DISMISSED);
        logAction(admin, "DISMISS_REPORT", "REPORT", reportId, null);
    }

    @Transactional
    public void upholdCommentReport(Long reportId, User admin, String reason) {
        requireAdmin(admin);
        validateReason(reason);
        CommentReport report = commentReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found"));
        Comment comment = report.getComment();
        em.refresh(comment, LockModeType.PESSIMISTIC_WRITE);
        em.refresh(report);
        if (report.getStatus() != ReportStatus.OPEN) return;
        commentReportRepository.updateStatusByCommentId(comment.getId(), ReportStatus.OPEN, ReportStatus.UPHELD);
        if (comment.isDeleted()) return;
        commentService.delete(comment.getId(), admin);
        notifyUser(comment.getAuthor(), null, admin,
                message("A moderator deleted your comment on " + comment.getProject().getTitle() + ".", reason));
        logAction(admin, "UPHOLD_REPORT", "COMMENT_REPORT", reportId, reason);
    }

    public List<AdminUserDTO> searchUsers(String query, User admin) {
        requireAdmin(admin);
        String q = query == null ? "" : query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return userRepository.searchByUsernameOrEmail(q, PageRequest.of(0, ADMIN_SEARCH_LIMIT)).stream()
                .map(u -> new AdminUserDTO(u.getId(), u.getUsername(), u.getEmail(), u.getRole().name(), u.isBanned()))
                .toList();
    }

    @Transactional
    public void banUser(Long userId, User admin, String reason) {
        requireAdmin(admin);
        validateReason(reason);
        User target = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (target.getId().equals(admin.getId()) || target.getRole() == Role.ADMIN) throw new AccessDeniedException("Cannot ban an administrator");
        if (target.isBanned()) return;
        target.setBanned(true);
        target.setSessionVersion(UUID.randomUUID());
        userRepository.save(target);
        refreshTokenRepository.deleteByUserId(userId);
        notifyUser(target, null, admin, message("Your account has been banned.", reason));
        notifications.revokeSessionsAfterCommit(userId);
        logAction(admin, "BAN", "USER", userId, reason);
    }

    @Transactional
    public void unbanUser(Long userId, User admin) {
        requireAdmin(admin);
        User target = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!target.isBanned()) return;
        target.setBanned(false);
        userRepository.save(target);
        notifyUser(target, null, admin, "Your account has been unbanned.");
        logAction(admin, "UNBAN", "USER", userId, null);
    }

    @Transactional
    public void unpublishProject(Long projectId, User admin, String reason) {
        requireAdmin(admin);
        validateReason(reason);
        Project project = em.find(Project.class, projectId, LockModeType.PESSIMISTIC_WRITE);
        if (project == null) throw new ResourceNotFoundException("Project not found");
        if (project.isModerationBlocked()) return;
        project.setVisibility(ProjectVisibility.PRIVATE);
        project.setModerationBlocked(true);
        projectRepository.save(project);
        projectReportRepository.updateStatusByProjectId(projectId, ReportStatus.OPEN, ReportStatus.UPHELD);
        notifyUser(project.getOwner(), project, admin,
                message("A moderator unpublished " + project.getTitle() + ". Publishing is blocked until a moderator allows it.", reason));
        logAction(admin, "UNPUBLISH_PROJECT", "PROJECT", projectId, reason);
    }

    @Transactional
    public void allowPublishing(Long projectId, User admin) {
        requireAdmin(admin);
        Project project = em.find(Project.class, projectId, LockModeType.PESSIMISTIC_WRITE);
        if (project == null) throw new ResourceNotFoundException("Project not found");
        if (!project.isModerationBlocked()) return;
        project.setModerationBlocked(false);
        notifyUser(project.getOwner(), project, admin, "A moderator allowed publishing " + project.getTitle() + " again.");
        logAction(admin, "ALLOW_PUBLISHING", "PROJECT", projectId, null);
    }

    private void notifyUser(User recipient, Project project, User admin, String notice) {
        if (recipient == null) return;
        notifications.recordModeration(recipient, project, admin, notice);
        String email = recipient.getEmail();
        String username = recipient.getUsername();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { emailService.sendModerationEmail(email, username, notice); }
        });
    }

    private void requireAdmin(User admin) {
        if (admin == null || admin.getRole() != Role.ADMIN) throw new AccessDeniedException("Administrator access required");
    }

    private void validateReason(String reason) {
        if (reason != null && reason.length() > 255) throw new RequestValidationException(RequestErrorCode.VALUE_SIZE_INVALID);
    }

    private String message(String action, String reason) {
        return reason == null || reason.isBlank() ? action : action + " Reason: " + reason;
    }

    private void logAction(User admin, String action, String targetType, Long targetId, String reason) {
        ModerationLog log = new ModerationLog();
        log.setAdmin(admin);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setReason(reason);
        log.setCreatedDate(new Date());
        moderationLogRepository.save(log);
    }
}
