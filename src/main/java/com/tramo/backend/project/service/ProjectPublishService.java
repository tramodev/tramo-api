// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.common.ContentLimits;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.exception.RequestValidationException;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.upload.service.EditorImageService;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.dto.ProjectResponseDTO;
import com.tramo.backend.project.dto.ProjectSnapshotDetailDTO;
import com.tramo.backend.project.dto.ProjectSnapshotSummaryDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectSnapshot;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectSnapshotRepository;
import com.tramo.backend.project.snapshot.ProjectSnapshotData;
import com.tramo.backend.project.snapshot.SnapshotCapture;
import com.tramo.backend.project.snapshot.SnapshotLimits;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.FollowRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;

import java.util.Date;
import java.util.List;

@Service
public class ProjectPublishService {
    private final AccessGuard accessGuard;
    private final ProjectVersionCapture history;
    private final ProjectRepository projectRepository;
    private final TrailRepository trailRepository;
    private final ProjectSnapshotRepository projectSnapshotRepository;
    private final FollowRepository followRepository;
    private final NotificationService notificationService;
    private final BadgeService badgeService;
    private final ProjectResponseMapper responseMapper;
    private final EditorImageService editorImages;
    private final SubscriptionService subscriptions;
    private final SnapshotCapture snapshotCapture;
    private final SnapshotLimits snapshotLimits;

    public ProjectPublishService(AccessGuard accessGuard, ProjectRepository projectRepository,
                                  TrailRepository trailRepository,
                                  ProjectSnapshotRepository projectSnapshotRepository,
                                  FollowRepository followRepository, NotificationService notificationService,
                                  BadgeService badgeService, ProjectResponseMapper responseMapper,
                                  EditorImageService editorImages, SubscriptionService subscriptions,
                                  SnapshotCapture snapshotCapture, SnapshotLimits snapshotLimits, ProjectVersionCapture history) {
        this.accessGuard = accessGuard;
        this.history = history;
        this.projectRepository = projectRepository;
        this.trailRepository = trailRepository;
        this.projectSnapshotRepository = projectSnapshotRepository;
        this.followRepository = followRepository;
        this.notificationService = notificationService;
        this.badgeService = badgeService;
        this.responseMapper = responseMapper;
        this.editorImages = editorImages;
        this.subscriptions = subscriptions;
        this.snapshotCapture = snapshotCapture;
        this.snapshotLimits = snapshotLimits;
    }

    @Transactional
    public ProjectResponseDTO publish(Long id, User requester) {
        Project project = history.lock(id, requester);
        if (project.isModerationBlocked()) throw new AccessDeniedException("Publishing is blocked by moderation");
        if (project.getDescription() == null || project.getDescription().isBlank()) {
            throw new RequestValidationException(RequestErrorCode.PROJECT_DESCRIPTION_REQUIRED);
        }
        ProjectVisibility previousVisibility = project.getVisibility();
        boolean firstPublish = project.getFirstPublishedDate() == null;
        project.setVisibility(ProjectVisibility.PUBLISHED);
        if (firstPublish) {
            project.setFirstPublishedDate(new Date());
        }
        if (previousVisibility != ProjectVisibility.PUBLISHED) {
            project.setLastPublishedDate(firstPublish ? project.getFirstPublishedDate() : new Date());
        }
        project.setModifiedDate(new Date());
        project = projectRepository.save(project);

        trailRepository.bumpVersionsByProjectId(project.getId());
        project = projectRepository.findById(project.getId()).orElseThrow();
        createSnapshot(project, "PUBLISH");
        badgeService.checkAndAwardBadges(project.getOwner());
        if (firstPublish) {
            notifyFollowers(project.getOwner(), "PUBLISH", project);
        }
        return responseMapper.toResponse(project, responseMapper.liveTagNames(project));
    }

    void applyVisibilityTransition(Project project, ProjectVisibility previousVisibility, boolean firstPublish,
                                    Date firstPublishTimestamp) {
        badgeService.checkAndAwardBadges(project.getOwner());
        if (previousVisibility != ProjectVisibility.PUBLISHED) {
            trailRepository.bumpVersionsByProjectId(project.getId());
            project = projectRepository.findById(project.getId()).orElseThrow();
            createSnapshot(project, "PUBLISH");
            project.setLastPublishedDate(firstPublish ? firstPublishTimestamp : new Date());
        }

        if (previousVisibility != ProjectVisibility.PUBLISHED && firstPublish) {
            notifyFollowers(project.getOwner(), "PUBLISH", project);
        }
    }

    @Transactional
    public void shareProject(Long id, User sharer) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        accessGuard.assertViewable(project, sharer);
        notifyFollowers(sharer, "SHARE", project);
    }

    private void notifyFollowers(User actor, String type, Project project) {
        notificationService.recordEventForAll(
                followRepository.findFollowersByFollowedId(actor.getId()), type, project, actor);
    }

    void createSnapshot(Project project, String trigger) {
        subscriptions.lockStorage(project.getOwner());
        projectRepository.flush();
        boolean supporter = subscriptions.isSupporter(project.getOwner());
        ProjectSnapshotData data = snapshotCapture.capture(project.getId(),
                subscriptions.remainingStorageBytes(project.getOwner(), supporter));

        ProjectSnapshot snapshot = new ProjectSnapshot();
        snapshot.setProject(project);
        snapshot.setTrigger(trigger);
        if ("PUBLISH".equals(trigger)) {
            snapshot.setVersion(projectSnapshotRepository.findMaxVersion(project.getId()).orElse(0) + 1);
        }
        snapshot.setContent(snapshotLimits.write(data));
        subscriptions.assertUploadAllowed(project.getOwner(), ContentLimits.bytes(snapshot.getContent()),
                supporter);
        snapshot.setCreatedDate(new Date());
        projectSnapshotRepository.save(snapshot);
        editorImages.retainSnapshot(snapshot);
    }

    public List<ProjectSnapshotSummaryDTO> listSnapshots(Long id, User requester) {
        accessGuard.getOwnedProject(id, requester);
        return projectSnapshotRepository.findByProjectIdAndTriggerOrderByVersionDesc(id, "PUBLISH");
    }

    public ProjectSnapshotDetailDTO getSnapshotDetail(Long id, Long snapshotId, User requester) {
        accessGuard.getOwnedProject(id, requester);
        ProjectSnapshot snapshot = projectSnapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new ResourceNotFoundException("Version not found"));
        if (!snapshot.getProject().getId().equals(id)) {
            throw new ResourceNotFoundException("Version not found");
        }
        ProjectSnapshotData content = snapshotLimits.read(snapshot.getContent());
        return new ProjectSnapshotDetailDTO(snapshot.getId(), snapshot.getVersion(), snapshot.getCreatedDate(), content);
    }

    public ProjectSnapshotDetailDTO getPublicSnapshotDetail(Long id, Long snapshotId, User requester) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Version not found"));
        accessGuard.assertViewable(project, requester);
        if (project.getFirstPublishedDate() == null) {
            throw new ResourceNotFoundException("Version not found");
        }
        ProjectSnapshot snapshot = projectSnapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new ResourceNotFoundException("Version not found"));
        if (!snapshot.getProject().getId().equals(id) || !"PUBLISH".equals(snapshot.getTrigger())) {
            throw new ResourceNotFoundException("Version not found");
        }
        ProjectSnapshotData content = snapshotLimits.read(snapshot.getContent());
        return new ProjectSnapshotDetailDTO(snapshot.getId(), snapshot.getVersion(), snapshot.getCreatedDate(), content);
    }
}
