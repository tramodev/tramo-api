// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.comment.repository.CommentRepository;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.project.dto.PublicItemDTO;
import com.tramo.backend.project.dto.PublicProjectResponseDTO;
import com.tramo.backend.project.dto.PublicTrailDTO;
import com.tramo.backend.project.dto.SitemapProjectDTO;
import com.tramo.backend.project.dto.SitemapUserDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectSnapshot;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.repository.ProjectBookmarkRepository;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectSnapshotRepository;
import com.tramo.backend.project.repository.ProjectViewRepository;
import com.tramo.backend.project.repository.ProjectVoteRepository;
import com.tramo.backend.project.snapshot.ProjectSnapshotData;
import com.tramo.backend.project.snapshot.SnapshotCapture;
import com.tramo.backend.project.snapshot.SnapshotLimits;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserBadgeRepository;
import com.tramo.backend.user.repository.UserRepository;
import com.tramo.backend.user.service.PrivacyPolicy;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class PublicProjectService {
    private final AccessGuard accessGuard;
    private final ProjectRepository projectRepository;
    private final ProjectViewRepository projectViewRepository;
    private final ProjectVoteRepository projectVoteRepository;
    private final ProjectBookmarkRepository projectBookmarkRepository;
    private final ProjectSnapshotRepository projectSnapshotRepository;
    private final CommentRepository commentRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final UserRepository userRepository;
    private final BadgeService badgeService;
    private final ProjectThumbnailResolver thumbnailResolver;
    private final ProjectIdCodec projectIdCodec;
    private final PrivacyPolicy privacyPolicy;
    private final SnapshotLimits snapshotLimits;
    private final SnapshotCapture snapshotCapture;
    private final ProjectShareLinkService shareLinkService;

    public PublicProjectService(AccessGuard accessGuard, ProjectRepository projectRepository,
                                 ProjectViewRepository projectViewRepository, ProjectVoteRepository projectVoteRepository,
                                 ProjectBookmarkRepository projectBookmarkRepository,
                                 ProjectSnapshotRepository projectSnapshotRepository,
                                 CommentRepository commentRepository, UserBadgeRepository userBadgeRepository,
                                 UserRepository userRepository, BadgeService badgeService,
                                 ProjectThumbnailResolver thumbnailResolver, ProjectIdCodec projectIdCodec,
                                 PrivacyPolicy privacyPolicy, SnapshotLimits snapshotLimits, SnapshotCapture snapshotCapture,
                                 ProjectShareLinkService shareLinkService) {
        this.accessGuard = accessGuard;
        this.projectRepository = projectRepository;
        this.projectViewRepository = projectViewRepository;
        this.projectVoteRepository = projectVoteRepository;
        this.projectBookmarkRepository = projectBookmarkRepository;
        this.projectSnapshotRepository = projectSnapshotRepository;
        this.commentRepository = commentRepository;
        this.userBadgeRepository = userBadgeRepository;
        this.userRepository = userRepository;
        this.badgeService = badgeService;
        this.thumbnailResolver = thumbnailResolver;
        this.projectIdCodec = projectIdCodec;
        this.privacyPolicy = privacyPolicy;
        this.snapshotLimits = snapshotLimits;
        this.snapshotCapture = snapshotCapture;
        this.shareLinkService = shareLinkService;
    }

    @Transactional
    public PublicProjectResponseDTO getPublicProject(Long id, User requester, String anonId) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        accessGuard.assertViewable(project, requester);

        String viewerKey = requester != null ? "user:" + requester.getId()
                : anonId != null && !anonId.isBlank() ? "anon:" + anonId
                : null;
        long viewCount = project.getViewCount();
        if (viewerKey != null && projectViewRepository.insertIfAbsent(id, viewerKey) == 1) {
            projectRepository.incrementViewCount(id);
            viewCount++;

            if (userBadgeRepository.countByUserIdAndBadgeCodeIn(
                            project.getOwner().getId(), BadgeService.VIEW_BADGE_CODES) < BadgeService.VIEW_BADGE_CODES.size()) {
                long viewsAfter = projectRepository.sumViewCountByOwnerIdAndPublished(project.getOwner().getId());
                if (badgeService.crossedViewBadgeThreshold(viewsAfter - 1, viewsAfter)) {
                    badgeService.checkAndAwardBadges(project.getOwner());
                }
            }
        }

        ProjectSnapshot snapshot = projectSnapshotRepository.findLatestPublishByProjectId(id)
                .orElseThrow(() -> new ResourceNotFoundException("Published version unavailable; owner must publish again"));
        return toResponse(project, requester, snapshotLimits.read(snapshot.getContent()), snapshot.getCreatedDate(), false, viewCount,
                privacyPolicy.canFork(project.getOwner()), privacyPolicy.canComment(project.getOwner(), requester));
    }

    @Transactional
    public PublicProjectResponseDTO getSharedProject(String token, User requester) {
        Project project = shareLinkService.viewable(token, requester);
        return toResponse(project, requester, snapshotCapture.capture(project.getId()), project.getModifiedDate(), true,
                project.getViewCount(), false, false);
    }

    private PublicProjectResponseDTO toResponse(Project project, User requester, ProjectSnapshotData data, Date displayDate,
                                                boolean live, long viewCount, boolean canFork, boolean canComment) {
        Long id = project.getId();
        List<PublicTrailDTO> trails = data.trails().stream()
                .map(t -> new PublicTrailDTO(
                        t.id(), t.title(), t.description(), t.version(),
                        t.forkedFromId() != null ? String.valueOf(t.forkedFromId()) : null,
                        t.items().stream().map(item -> toPublicItem(item, live)).toList()
                ))
                .toList();
        List<PublicItemDTO> looseItems = data.looseItems().stream().map(item -> toPublicItem(item, live)).toList();

        Project publicSource = project.getForkedFrom();
        if (publicSource != null && !privacyPolicy.isProjectViewable(publicSource, requester)) {
            publicSource = null;
        }
        return new PublicProjectResponseDTO(
                projectIdCodec.encode(id),
                data.title(),
                data.description(),
                project.getOwner().getUsername(),
                displayDate,
                thumbnailResolver.resolvePublished(data).imageUrl(),
                trails,
                looseItems,
                projectVoteRepository.countByProjectId(id),
                requester != null && projectVoteRepository.findByProjectIdAndUserId(id, requester.getId()).isPresent(),
                requester != null && projectBookmarkRepository.findByProjectIdAndUserId(id, requester.getId()).isPresent(),
                viewCount,
                commentRepository.countGroupedByProjectIdIn(List.of(id)).stream()
                        .findFirst()
                        .map(CommentRepository.ProjectCommentCount::getCommentCount)
                        .orElse(0L),
                project.getVisibility().toJson(),
                publicSource != null ? projectIdCodec.encode(publicSource.getId()) : null,
                publicSource != null ? project.getForkedFromTitle() : null,
                publicSource != null ? publicSource.getOwner().getUsername() : null,
                canFork,
                canComment
        );
    }

    private PublicItemDTO toPublicItem(ProjectSnapshotData.ItemData item, boolean live) {
        return new PublicItemDTO(item.id(), item.title(), item.type(),
                live && item.content() == null ? "" : item.content(), item.titleAlign());
    }

    public List<SitemapProjectDTO> getSitemapProjects() {
        return projectRepository.findByVisibilityOrderByLastPublishedDateDesc(ProjectVisibility.PUBLISHED).stream()
                .map(project -> new SitemapProjectDTO(projectIdCodec.encode(project.getId()), project.getModifiedDate()))
                .toList();
    }

    public List<SitemapUserDTO> getSitemapUsers() {
        return userRepository.findByVisibilityTrueAndBannedFalse().stream()
                .map(user -> new SitemapUserDTO(user.getUsername(), user.getUpdatedAt()))
                .toList();
    }
}
