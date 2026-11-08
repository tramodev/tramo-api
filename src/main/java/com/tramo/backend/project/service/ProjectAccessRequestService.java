// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.notification.NotificationTypes;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.dto.ProjectAccessRequestDTO;
import com.tramo.backend.project.dto.ProjectMemberDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectAccessRequest;
import com.tramo.backend.project.entity.ProjectRole;
import com.tramo.backend.project.repository.ProjectAccessRequestRepository;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.BlockedUserRepository;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class ProjectAccessRequestService {
    private final AccessGuard accessGuard;
    private final ProjectRepository projectRepository;
    private final ProjectAccessRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final BlockedUserRepository blockedUserRepository;
    private final NotificationService notificationService;
    private final ProjectMemberService memberService;

    public ProjectAccessRequestService(AccessGuard accessGuard, ProjectRepository projectRepository, ProjectAccessRequestRepository requestRepository,
                                       UserRepository userRepository, BlockedUserRepository blockedUserRepository,
                                       NotificationService notificationService, ProjectMemberService memberService) {
        this.accessGuard = accessGuard;
        this.projectRepository = projectRepository;
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
        this.blockedUserRepository = blockedUserRepository;
        this.notificationService = notificationService;
        this.memberService = memberService;
    }

    @Transactional
    public void request(Long projectId, User requester) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) return;
        User owner = project.getOwner();
        if (owner.isBanned() || accessGuard.roleOf(project, requester) != null
                || blockedUserRepository.existsEitherDirection(owner.getId(), requester.getId())
                || requestRepository.existsByProjectIdAndRequesterId(projectId, requester.getId())) {
            return;
        }
        ProjectAccessRequest request = new ProjectAccessRequest();
        request.setProject(project);
        request.setRequester(requester);
        request.setCreatedAt(new Date());
        requestRepository.save(request);
        notificationService.recordEvent(owner, NotificationTypes.ACCESS_REQUEST, project, requester, request.getCreatedAt());
    }

    public List<ProjectAccessRequestDTO> list(Long projectId, User requester) {
        accessGuard.getOwnedProject(projectId, requester);
        return requestRepository.findWithRequesterByProjectId(projectId).stream()
                .map(r -> new ProjectAccessRequestDTO(r.getRequester().getUsername(), r.getRequester().getImageUrl(), r.getCreatedAt()))
                .toList();
    }

    @Transactional
    public ProjectMemberDTO approve(Long projectId, String username, ProjectRole role, User requester) {
        accessGuard.getOwnedProject(projectId, requester);
        findRequester(projectId, username);
        return memberService.add(projectId, username, role, requester);
    }

    @Transactional
    public void deny(Long projectId, String username, User requester) {
        accessGuard.getOwnedProject(projectId, requester);
        requestRepository.deleteByProjectIdAndRequesterId(projectId, findRequester(projectId, username).getId());
    }

    private User findRequester(Long projectId, String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .filter(user -> requestRepository.existsByProjectIdAndRequesterId(projectId, user.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Access request not found"));
    }
}
