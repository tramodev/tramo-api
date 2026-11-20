// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.dto.ProjectResponseDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectMember;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.repository.ProjectMemberRepository;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.user.entity.User;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class ProjectTrashService {
    static final long RETENTION_DAYS = 30;
    private static final Logger log = LoggerFactory.getLogger(ProjectTrashService.class);

    private final AccessGuard accessGuard;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final ProjectMemberService memberService;
    private final ProjectService projectService;
    private final ProjectResponseMapper responseMapper;
    private final NotificationService notificationService;

    public ProjectTrashService(AccessGuard accessGuard, ProjectRepository projectRepository, ProjectMemberRepository memberRepository,
                               ProjectMemberService memberService, ProjectService projectService, ProjectResponseMapper responseMapper,
                               NotificationService notificationService) {
        this.accessGuard = accessGuard;
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
        this.memberService = memberService;
        this.projectService = projectService;
        this.responseMapper = responseMapper;
        this.notificationService = notificationService;
    }

    public List<ProjectResponseDTO> list(User requester) {
        return projectRepository.findByOwnerIdAndTrashedAtNotNullOrderByTrashedAtDesc(requester.getId()).stream()
                .map(responseMapper::toResponse)
                .toList();
    }

    @Transactional
    public void trash(Long id, User requester) {
        Project project = accessGuard.getOwnedProject(id, requester);
        project.setTrashedAt(new Date());
        project.setVisibility(ProjectVisibility.PRIVATE);
        projectRepository.save(project);
        List<User> members = memberRepository.findWithUserByProjectId(id).stream().map(ProjectMember::getUser).toList();
        notificationService.recordProjectTrashed(members, project);
        List<Long> userIds = new ArrayList<>(members.stream().map(User::getId).toList());
        userIds.add(requester.getId());
        memberService.notifyRevoked(userIds, id);
    }

    @Transactional
    public void restore(Long id, User requester) {
        Project project = accessGuard.getOwnedProjectIncludingTrash(id, requester);
        if (project.getTrashedAt() == null) {
            throw new ResourceNotFoundException("Project not found");
        }
        project.setTrashedAt(null);
        projectRepository.save(project);
    }

    @Scheduled(cron = "0 15 3 * * *")
    public void purgeExpired() {
        Date cutoff = new Date(System.currentTimeMillis() - RETENTION_DAYS * 24L * 60 * 60 * 1000);
        for (Project project : projectRepository.findByTrashedAtBefore(cutoff)) {
            try {
                projectService.delete(project.getId(), project.getOwner());
            } catch (RuntimeException failure) {
                log.warn("purgeExpired failed for project {}", project.getId(), failure);
            }
        }
    }
}
