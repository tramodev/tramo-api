package com.mypath.backend.moderation.service;

import com.mypath.backend.exception.ResourceNotFoundException;
import com.mypath.backend.moderation.dto.AdminUserDTO;
import com.mypath.backend.moderation.dto.ReportDTO;
import com.mypath.backend.moderation.entity.ModerationLog;
import com.mypath.backend.moderation.entity.ProjectReport;
import com.mypath.backend.moderation.repository.ModerationLogRepository;
import com.mypath.backend.moderation.repository.ProjectReportRepository;
import com.mypath.backend.project.entity.Project;
import com.mypath.backend.project.repository.ProjectRepository;
import com.mypath.backend.user.entity.User;
import com.mypath.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class ModerationService {
    private final ProjectReportRepository projectReportRepository;
    private final ModerationLogRepository moderationLogRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public ModerationService(ProjectReportRepository projectReportRepository,
                              ModerationLogRepository moderationLogRepository,
                              ProjectRepository projectRepository,
                              UserRepository userRepository) {
        this.projectReportRepository = projectReportRepository;
        this.moderationLogRepository = moderationLogRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public void submitReport(Long projectId, User reporter, String reason) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        if (project.getOwner().getId().equals(reporter.getId())) {
            throw new AccessDeniedException("Cannot report your own project");
        }
        if (projectReportRepository.existsByProjectIdAndReporterIdAndStatus(projectId, reporter.getId(), "OPEN")) {
            return;
        }

        ProjectReport report = new ProjectReport();
        report.setProject(project);
        report.setReporter(reporter);
        report.setReason(reason);
        report.setStatus("OPEN");
        report.setCreatedDate(new Date());
        projectReportRepository.save(report);
    }

    public List<ReportDTO> listOpenReports() {
        return projectReportRepository.findByStatusOrderByCreatedDateDesc("OPEN").stream()
                .map(r -> new ReportDTO(
                        r.getId(),
                        r.getProject().getId(),
                        r.getProject().getTitle(),
                        r.getReporter().getUsername(),
                        r.getReason(),
                        r.getStatus(),
                        r.getCreatedDate()
                ))
                .toList();
    }

    @Transactional
    public void dismissReport(Long reportId, User admin) {
        ProjectReport report = projectReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found"));
        report.setStatus("DISMISSED");
        logAction(admin, "DISMISS_REPORT", "REPORT", reportId, null);
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
        project.setVisibility("private");
        projectRepository.save(project);

        for (ProjectReport report : projectReportRepository.findByStatusOrderByCreatedDateDesc("OPEN")) {
            if (report.getProject().getId().equals(projectId)) {
                report.setStatus("ACTIONED");
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
