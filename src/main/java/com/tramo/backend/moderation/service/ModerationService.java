package com.tramo.backend.moderation.service;

import com.tramo.backend.auth.repository.RefreshTokenRepository;
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
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

@Service
public class ModerationService {
    private final ProjectReportRepository projectReportRepository;
    private final CommentReportRepository commentReportRepository;
    private final ModerationLogRepository moderationLogRepository;
    private final ProjectRepository projectRepository;
    private final CommentRepository commentRepository;
    private final CommentService commentService;
    private final UserRepository userRepository;
    private final ProjectIdCodec projectIdCodec;
    private final RefreshTokenRepository refreshTokenRepository;

    public ModerationService(ProjectReportRepository projectReportRepository,
                              CommentReportRepository commentReportRepository,
                              ModerationLogRepository moderationLogRepository,
                              ProjectRepository projectRepository,
                              CommentRepository commentRepository,
                              CommentService commentService,
                              UserRepository userRepository,
                              ProjectIdCodec projectIdCodec,
                              RefreshTokenRepository refreshTokenRepository) {
        this.projectReportRepository = projectReportRepository;
        this.commentReportRepository = commentReportRepository;
        this.moderationLogRepository = moderationLogRepository;
        this.projectRepository = projectRepository;
        this.commentRepository = commentRepository;
        this.commentService = commentService;
        this.userRepository = userRepository;
        this.projectIdCodec = projectIdCodec;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional
    public void submitReport(Long projectId, User reporter, String reason) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        if (project.getOwner().getId().equals(reporter.getId())) {
            throw new AccessDeniedException("Cannot report your own project");
        }
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
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment not found"));

        if (comment.getAuthor() != null && comment.getAuthor().getId().equals(reporter.getId())) {
            throw new AccessDeniedException("Cannot report your own comment");
        }
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

    public List<ReportDTO> listOpenReports() {
        Stream<ReportDTO> projectReports = projectReportRepository.findOpenRows(ReportStatus.OPEN).stream()
                .map(r -> new ReportDTO(
                        (Long) r[0],
                        "PROJECT",
                        projectIdCodec.encode((Long) r[1]),
                        (String) r[2],
                        null,
                        null,
                        (String) r[3],
                        (String) r[4],
                        ((ReportStatus) r[5]).name(),
                        (Date) r[6]
                ));
        Stream<ReportDTO> commentReports = commentReportRepository.findOpenRows(ReportStatus.OPEN).stream()
                .map(r -> new ReportDTO(
                        (Long) r[0],
                        "COMMENT",
                        projectIdCodec.encode((Long) r[1]),
                        (String) r[2],
                        (Long) r[3],
                        (String) r[4],
                        (String) r[5],
                        (String) r[6],
                        ((ReportStatus) r[7]).name(),
                        (Date) r[8]
                ));
        return Stream.concat(projectReports, commentReports)
                .sorted(Comparator.comparing(ReportDTO::getCreatedDate).reversed())
                .toList();
    }

    @Transactional
    public void dismissReport(Long reportId, String type, User admin) {
        if ("COMMENT".equals(type)) {
            CommentReport report = commentReportRepository.findById(reportId)
                    .orElseThrow(() -> new ResourceNotFoundException("Report not found"));
            report.setStatus(ReportStatus.DISMISSED);
            logAction(admin, "DISMISS_REPORT", "COMMENT_REPORT", reportId, null);
            return;
        }
        ProjectReport report = projectReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found"));
        report.setStatus(ReportStatus.DISMISSED);
        logAction(admin, "DISMISS_REPORT", "REPORT", reportId, null);
    }

    @Transactional
    public void upholdCommentReport(Long reportId, User admin, String reason) {
        CommentReport report = commentReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found"));
        Comment comment = report.getComment();
        commentService.delete(comment.getId(), admin);

        for (CommentReport r : commentReportRepository.findByStatusOrderByCreatedDateDesc(ReportStatus.OPEN)) {
            if (r.getComment().getId().equals(comment.getId())) {
                r.setStatus(ReportStatus.UPHELD);
            }
        }

        logAction(admin, "UPHOLD_REPORT", "COMMENT_REPORT", reportId, reason);
    }

    public List<AdminUserDTO> searchUsers(String query) {
        String q = query == null ? "" : query.trim();
        List<User> users = q.isEmpty()
                ? userRepository.findAll()
                : userRepository.findByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase(q, q);
        return users.stream()
                .map(u -> new AdminUserDTO(u.getId(), u.getUsername(), u.getEmail(), u.getRole().name(), u.isBanned()))
                .limit(50)
                .toList();
    }

    @Transactional
    public void banUser(Long userId, User admin, String reason) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (target.getId().equals(admin.getId())) {
            throw new AccessDeniedException("Cannot ban yourself");
        }
        target.setBanned(true);
        userRepository.save(target);
        refreshTokenRepository.deleteByUserId(userId);
        logAction(admin, "BAN", "USER", userId, reason);
    }

    @Transactional
    public void unbanUser(Long userId, User admin) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        target.setBanned(false);
        userRepository.save(target);
        logAction(admin, "UNBAN", "USER", userId, null);
    }

    @Transactional
    public void unpublishProject(Long projectId, User admin, String reason) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        project.setVisibility(ProjectVisibility.PRIVATE);
        projectRepository.save(project);

        for (ProjectReport report : projectReportRepository.findByStatusOrderByCreatedDateDesc(ReportStatus.OPEN)) {
            if (report.getProject().getId().equals(projectId)) {
                report.setStatus(ReportStatus.UPHELD);
            }
        }

        logAction(admin, "UNPUBLISH_PROJECT", "PROJECT", projectId, reason);
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
