package com.tramo.backend.project.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.dto.BookmarkResponseDTO;
import com.tramo.backend.project.dto.VoteResponseDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectBookmark;
import com.tramo.backend.project.entity.ProjectVote;
import com.tramo.backend.project.repository.ProjectBookmarkRepository;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectVoteRepository;
import com.tramo.backend.user.entity.User;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class ProjectEngagementService {
    private final AccessGuard accessGuard;
    private final ProjectRepository projectRepository;
    private final ProjectVoteRepository projectVoteRepository;
    private final ProjectBookmarkRepository projectBookmarkRepository;
    private final NotificationService notificationService;
    private final BadgeService badgeService;

    public ProjectEngagementService(AccessGuard accessGuard, ProjectRepository projectRepository,
                                     ProjectVoteRepository projectVoteRepository,
                                     ProjectBookmarkRepository projectBookmarkRepository,
                                     NotificationService notificationService, BadgeService badgeService) {
        this.accessGuard = accessGuard;
        this.projectRepository = projectRepository;
        this.projectVoteRepository = projectVoteRepository;
        this.projectBookmarkRepository = projectBookmarkRepository;
        this.notificationService = notificationService;
        this.badgeService = badgeService;
    }

    @Transactional
    public VoteResponseDTO toggleVote(Long projectId, User requester, String voterIp, String deviceId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        var existingVote = projectVoteRepository.findByProjectIdAndUserId(projectId, requester.getId());
        boolean voted;
        if (existingVote.isPresent()) {
            projectVoteRepository.delete(existingVote.get());
            voted = false;
        } else {
            accessGuard.assertViewable(project, requester);
            ProjectVote vote = new ProjectVote();
            vote.setProject(project);
            vote.setUser(requester);
            vote.setCreatedDate(new Date());
            vote.setVoterIp(voterIp);
            vote.setDeviceId(deviceId);
            projectVoteRepository.save(vote);
            voted = true;
            notificationService.recordEvent(project.getOwner(), "UPVOTE", project, requester);
            badgeService.checkAndAwardBadges(project.getOwner());
        }
        return new VoteResponseDTO(voted, projectVoteRepository.countByProjectId(projectId));
    }

    @Transactional
    public BookmarkResponseDTO toggleBookmark(Long projectId, User requester) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        var existingBookmark = projectBookmarkRepository.findByProjectIdAndUserId(projectId, requester.getId());
        boolean bookmarked;
        if (existingBookmark.isPresent()) {
            projectBookmarkRepository.delete(existingBookmark.get());
            bookmarked = false;
        } else {
            accessGuard.assertViewable(project, requester);
            ProjectBookmark bookmark = new ProjectBookmark();
            bookmark.setProject(project);
            bookmark.setUser(requester);
            bookmark.setCreatedDate(new Date());
            projectBookmarkRepository.save(bookmark);
            bookmarked = true;
        }
        return new BookmarkResponseDTO(bookmarked);
    }
}
