// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectRole;
import com.tramo.backend.project.repository.ProjectMemberRepository;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import com.tramo.backend.user.service.PrivacyPolicy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class AccessGuard {
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final PrivacyPolicy privacyPolicy;
    private final ProjectMemberRepository memberRepository;

    public AccessGuard(ProjectRepository projectRepository, UserRepository userRepository, PrivacyPolicy privacyPolicy,
                       ProjectMemberRepository memberRepository) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.privacyPolicy = privacyPolicy;
        this.memberRepository = memberRepository;
    }

    public Project getOwnedProject(Long id, User requester) {
        return getProject(id, requester, ProjectRole.OWNER);
    }

    public Project getProject(Long id, User requester, ProjectRole min) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        assertRole(project, requester, min);
        return project;
    }

    public void assertRole(Project project, User requester, ProjectRole min) {
        ProjectRole role = roleOf(project, requester);
        if (role == null) {
            throw new ResourceNotFoundException("Project not found");
        }
        if (!role.atLeast(min)) {
            throw new AccessDeniedException("Insufficient project role");
        }
    }

    public boolean hasRole(Project project, User requester, ProjectRole min) {
        ProjectRole role = roleOf(project, requester);
        return role != null && role.atLeast(min);
    }

    public ProjectRole roleOf(Project project, User requester) {
        if (requester == null) {
            return null;
        }
        if (project.getOwner().getId().equals(requester.getId())) {
            return ProjectRole.OWNER;
        }
        ProjectRole role = memberRepository.findRole(project.getId(), requester.getId()).orElse(null);
        return role == null || project.getOwner().isBanned() ? null : role;
    }

    public void assertViewable(Project project, User requester) {
        privacyPolicy.assertProjectViewable(project, requester);
    }

    public User publicProfileTarget(String username, User requester) {
        User target = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!privacyPolicy.isProfileViewable(target, requester)) {
            throw new ResourceNotFoundException("User not found");
        }
        return target;
    }
}
