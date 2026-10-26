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
import java.util.Optional;

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

    public PublicProjectService(AccessGuard accessGuard, ProjectRepository projectRepository,
                                 ProjectViewRepository projectViewRepository, ProjectVoteRepository projectVoteRepository,
                                 ProjectBookmarkRepository projectBookmarkRepository,
                                 ProjectSnapshotRepository projectSnapshotRepository,
                                 CommentRepository commentRepository, UserBadgeRepository userBadgeRepository,
                                 UserRepository userRepository, BadgeService badgeService,
                                 ProjectThumbnailResolver thumbnailResolver, ProjectIdCodec projectIdCodec,
                                 PrivacyPolicy privacyPolicy, SnapshotLimits snapshotLimits, SnapshotCapture snapshotCapture) {
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

            if (project.getVisibility() == ProjectVisibility.PUBLISHED
                    && userBadgeRepository.countByUserIdAndBadgeCodeIn(
                            project.getOwner().getId(), BadgeService.VIEW_BADGE_CODES) < BadgeService.VIEW_BADGE_CODES.size()) {
                long viewsAfter = projectRepository.sumViewCountByOwnerIdAndPublished(project.getOwner().getId());
                if (badgeService.crossedViewBadgeThreshold(viewsAfter - 1, viewsAfter)) {
                    badgeService.checkAndAwardBadges(project.getOwner());
                }
            }
        }

        Optional<ProjectSnapshot> snapshotOpt = project.getVisibility() == ProjectVisibility.PUBLISHED
                ? projectSnapshotRepository.findLatestPublishByProjectId(id)
                : Optional.empty();

        if (project.getVisibility() == ProjectVisibility.PUBLISHED && snapshotOpt.isEmpty()) {
            throw new ResourceNotFoundException("Published version unavailable; owner must publish again");
        }
        ProjectSnapshotData data = snapshotOpt.isPresent() ? snapshotLimits.read(snapshotOpt.get().getContent())
                : snapshotCapture.capture(id);
        Date displayDate = snapshotOpt.isPresent() ? snapshotOpt.get().getCreatedDate() : project.getModifiedDate();
        List<PublicTrailDTO> trails = data.trails().stream()
                .map(t -> new PublicTrailDTO(
                        t.id(), t.title(), t.description(), t.version(),
                        t.forkedFromId() != null ? String.valueOf(t.forkedFromId()) : null,
                        t.items().stream().map(item -> toPublicItem(item, snapshotOpt.isEmpty())).toList()
                ))
                .toList();
        List<PublicItemDTO> looseItems = data.looseItems().stream().map(item -> toPublicItem(item, snapshotOpt.isEmpty())).toList();

        Project publicSource = project.getForkedFrom();
        if (publicSource != null && !privacyPolicy.isProjectViewable(publicSource, requester)) {
            publicSource = null;
        }
        return new PublicProjectResponseDTO(
                projectIdCodec.encode(project.getId()),
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
                privacyPolicy.canFork(project.getOwner()),
                privacyPolicy.canComment(project.getOwner(), requester)
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
