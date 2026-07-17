package com.mypath.backend.user.service;

import com.mypath.backend.auth.repository.EmailVerificationTokenRepository;
import com.mypath.backend.auth.repository.PasswordResetTokenRepository;
import com.mypath.backend.auth.repository.RefreshTokenRepository;
import com.mypath.backend.comment.repository.CommentRepository;
import com.mypath.backend.moderation.repository.CommentReportRepository;
import com.mypath.backend.moderation.repository.ModerationLogRepository;
import com.mypath.backend.moderation.repository.ProjectReportRepository;
import com.mypath.backend.notification.service.NotificationService;
import com.mypath.backend.project.entity.Project;
import com.mypath.backend.project.repository.ProjectBookmarkRepository;
import com.mypath.backend.project.repository.ProjectRepository;
import com.mypath.backend.project.repository.ProjectVoteRepository;
import com.mypath.backend.project.service.ProjectService;
import com.mypath.backend.user.entity.User;
import com.mypath.backend.user.repository.FollowRepository;
import com.mypath.backend.user.repository.UserBadgeRepository;
import com.mypath.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class UserAccountService {
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final ProjectVoteRepository projectVoteRepository;
    private final ProjectBookmarkRepository projectBookmarkRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final FollowRepository followRepository;
    private final ProjectReportRepository projectReportRepository;
    private final ModerationLogRepository moderationLogRepository;
    private final NotificationService notificationService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final CommentRepository commentRepository;
    private final CommentReportRepository commentReportRepository;

    public UserAccountService(UserRepository userRepository, ProjectRepository projectRepository,
                               ProjectService projectService, ProjectVoteRepository projectVoteRepository,
                               ProjectBookmarkRepository projectBookmarkRepository, UserBadgeRepository userBadgeRepository,
                               FollowRepository followRepository, ProjectReportRepository projectReportRepository,
                               ModerationLogRepository moderationLogRepository, NotificationService notificationService,
                               RefreshTokenRepository refreshTokenRepository,
                               PasswordResetTokenRepository passwordResetTokenRepository,
                               EmailVerificationTokenRepository emailVerificationTokenRepository,
                               CommentRepository commentRepository,
                               CommentReportRepository commentReportRepository) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.projectService = projectService;
        this.projectVoteRepository = projectVoteRepository;
        this.projectBookmarkRepository = projectBookmarkRepository;
        this.userBadgeRepository = userBadgeRepository;
        this.followRepository = followRepository;
        this.projectReportRepository = projectReportRepository;
        this.moderationLogRepository = moderationLogRepository;
        this.notificationService = notificationService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.commentRepository = commentRepository;
        this.commentReportRepository = commentReportRepository;
    }

    @Transactional
    public void deleteAccount(User user) {
        Long userId = user.getId();

        for (Project project : projectRepository.findByOwnerId(userId)) {
            projectService.delete(project.getId(), user);
        }

        projectVoteRepository.deleteByUserId(userId);
        projectBookmarkRepository.deleteByUserId(userId);
        userBadgeRepository.deleteByUserId(userId);
        followRepository.deleteByFollowerIdOrFollowedId(userId, userId);
        projectReportRepository.deleteByReporterId(userId);
        commentRepository.softDeleteByAuthorId(userId);
        commentReportRepository.deleteByReporterId(userId);
        notificationService.deleteAllForRecipient(userId);
        notificationService.clearLatestActorReferences(userId);
        moderationLogRepository.clearAdminReferences(userId);
        refreshTokenRepository.deleteByUserId(userId);
        passwordResetTokenRepository.deleteByUserId(userId);
        emailVerificationTokenRepository.deleteByUserId(userId);

        userRepository.delete(user);
    }
}
