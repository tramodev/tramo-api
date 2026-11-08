// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.collab.service.CollabClient;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.notification.NotificationTypes;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.dto.ProjectMemberDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectMember;
import com.tramo.backend.project.entity.ProjectRole;
import com.tramo.backend.project.entity.ProjectShareLink;
import com.tramo.backend.project.repository.ProjectAccessRequestRepository;
import com.tramo.backend.project.repository.ProjectMemberRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.BlockedUserRepository;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class ProjectMemberService {
    public static final String ACCESS_REVOKED_EVENT = "access-revoked";
    public static final String PROJECT_RESTORED_EVENT = "project-restored";

    private final AccessGuard accessGuard;
    private final ProjectMemberRepository memberRepository;
    private final ProjectAccessRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final BlockedUserRepository blockedUserRepository;
    private final NotificationService notificationService;
    private final ProjectIdCodec codec;
    private final CollabClient collab;
    private final int maxMembers;

    public ProjectMemberService(AccessGuard accessGuard, ProjectMemberRepository memberRepository, ProjectAccessRequestRepository requestRepository, UserRepository userRepository,
                                BlockedUserRepository blockedUserRepository, NotificationService notificationService,
                                ProjectIdCodec codec, CollabClient collab, @Value("${app.projects.max-members:20}") int maxMembers) {
        this.accessGuard = accessGuard;
        this.memberRepository = memberRepository;
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
        this.blockedUserRepository = blockedUserRepository;
        this.notificationService = notificationService;
        this.codec = codec;
        this.collab = collab;
        this.maxMembers = maxMembers;
    }

    public List<ProjectMemberDTO> list(Long projectId, User requester) {
        Project project = accessGuard.getProject(projectId, requester, ProjectRole.VIEWER);
        List<ProjectMemberDTO> members = new ArrayList<>();
        User owner = project.getOwner();
        members.add(new ProjectMemberDTO(owner.getUsername(), owner.getImageUrl(), ProjectRole.OWNER.name(), false));
        for (ProjectMember member : memberRepository.findWithUserByProjectId(projectId)) {
            members.add(toDto(member));
        }
        return members;
    }

    @Transactional
    public ProjectMemberDTO add(Long projectId, String username, ProjectRole role, User requester) {
        Project project = accessGuard.getOwnedProject(projectId, requester);
        User target = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (target.getId().equals(requester.getId())) {
            throw new RequestValidationException(RequestErrorCode.PROJECT_MEMBER_SELF);
        }
        if (blockedUserRepository.existsEitherDirection(requester.getId(), target.getId())) {
            throw new ResourceNotFoundException("User not found");
        }
        ProjectMember member = memberRepository.findByProjectIdAndUserId(projectId, target.getId()).orElse(null);
        if (member == null) {
            member = newMember(project, target, role, requester, null);
        } else {
            revokeIfDowngraded(member, role);
            member.setRole(role);
            member.setShareLink(null);
        }
        memberRepository.save(member);
        requestRepository.deleteByProjectIdAndRequesterId(projectId, target.getId());
        notificationService.recordEvent(target, NotificationTypes.MEMBER_ADDED, project, requester, new Date());
        return toDto(member);
    }

    @Transactional
    public ProjectMemberDTO changeRole(Long projectId, String username, ProjectRole role, User requester) {
        accessGuard.getOwnedProject(projectId, requester);
        ProjectMember member = findMember(projectId, username);
        revokeIfDowngraded(member, role);
        member.setRole(role);
        return toDto(memberRepository.save(member));
    }

    @Transactional
    public void remove(Long projectId, String username, User requester) {
        accessGuard.getOwnedProject(projectId, requester);
        ProjectMember member = findMember(projectId, username);
        memberRepository.delete(member);
        notifyRevoked(List.of(member.getUser().getId()), projectId);
    }

    @Transactional
    public void leave(Long projectId, User requester) {
        Project project = accessGuard.getProject(projectId, requester, ProjectRole.VIEWER);
        if (project.getOwner().getId().equals(requester.getId())) {
            throw new RequestValidationException(RequestErrorCode.PROJECT_MEMBER_SELF);
        }
        memberRepository.findByProjectIdAndUserId(projectId, requester.getId()).ifPresent(memberRepository::delete);
        notifyRevoked(List.of(requester.getId()), projectId);
    }

    ProjectMember newMember(Project project, User user, ProjectRole role, User invitedBy, ProjectShareLink shareLink) {
        if (memberRepository.countByProjectId(project.getId()) >= maxMembers) {
            throw new RequestValidationException(RequestErrorCode.PROJECT_MEMBER_LIMIT);
        }
        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setRole(role);
        member.setInvitedBy(invitedBy);
        member.setShareLink(shareLink);
        member.setCreatedAt(new Date());
        return member;
    }

    public void notifyRestored(Project project) {
        Map<String, String> payload = Map.of("projectId", codec.encode(project.getId()));
        notificationService.sendEvent(project.getOwner().getId(), PROJECT_RESTORED_EVENT, payload);
        memberRepository.findWithUserByProjectId(project.getId())
                .forEach(member -> notificationService.sendEvent(member.getUser().getId(), PROJECT_RESTORED_EVENT, payload));
    }

    void notifyRevoked(List<Long> userIds, Long projectId) {
        Map<String, String> payload = Map.of("projectId", codec.encode(projectId));
        userIds.forEach(id -> {
            notificationService.sendEvent(id, ACCESS_REVOKED_EVENT, payload);
            collab.revokeAfterCommit(id);
        });
    }

    private void revokeIfDowngraded(ProjectMember member, ProjectRole role) {
        if (member.getRole().atLeast(ProjectRole.EDITOR) && !role.atLeast(ProjectRole.EDITOR)) {
            collab.revokeAfterCommit(member.getUser().getId());
        }
    }

    private ProjectMember findMember(Long projectId, String username) {
        User target = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        return memberRepository.findByProjectIdAndUserId(projectId, target.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
    }

    private ProjectMemberDTO toDto(ProjectMember member) {
        User user = member.getUser();
        return new ProjectMemberDTO(user.getUsername(), user.getImageUrl(), member.getRole().name(), member.getShareLink() != null);
    }
}
