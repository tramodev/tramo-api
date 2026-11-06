// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.auth.service.OpaqueToken;
import com.tramo.backend.collab.service.CollabClient;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.notification.NotificationTypes;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.dto.ShareLinkDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectMember;
import com.tramo.backend.project.entity.ProjectRole;
import com.tramo.backend.project.entity.ProjectShareLink;
import com.tramo.backend.project.repository.ProjectMemberRepository;
import com.tramo.backend.project.repository.ProjectShareLinkRepository;
import com.tramo.backend.security.crypto.EncryptedStringConverter;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.BlockedUserRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class ProjectShareLinkService {
    private final AccessGuard accessGuard;
    private final ProjectShareLinkRepository linkRepository;
    private final ProjectMemberRepository memberRepository;
    private final ProjectMemberService memberService;
    private final BlockedUserRepository blockedUserRepository;
    private final NotificationService notificationService;
    private final ProjectIdCodec codec;
    private final EncryptedStringConverter cipher;
    private final CollabClient collab;

    public ProjectShareLinkService(AccessGuard accessGuard, ProjectShareLinkRepository linkRepository,
                                   ProjectMemberRepository memberRepository, ProjectMemberService memberService,
                                   BlockedUserRepository blockedUserRepository, NotificationService notificationService,
                                   ProjectIdCodec codec, EncryptedStringConverter cipher, CollabClient collab) {
        this.accessGuard = accessGuard;
        this.linkRepository = linkRepository;
        this.memberRepository = memberRepository;
        this.memberService = memberService;
        this.blockedUserRepository = blockedUserRepository;
        this.notificationService = notificationService;
        this.codec = codec;
        this.cipher = cipher;
        this.collab = collab;
    }

    public ShareLinkDTO get(Long projectId, User requester) {
        accessGuard.getOwnedProject(projectId, requester);
        return linkRepository.findByProjectIdAndRevokedAtIsNull(projectId).map(this::toDto).orElse(null);
    }

    @Transactional
    public ShareLinkDTO set(Long projectId, ProjectRole role, User requester) {
        Project project = accessGuard.getOwnedProject(projectId, requester);
        ProjectShareLink link = linkRepository.findActiveForUpdate(projectId).orElse(null);
        if (link == null) {
            link = newLink(project, requester);
        } else if (link.getRole() != role) {
            if (link.getRole().atLeast(ProjectRole.EDITOR) && !role.atLeast(ProjectRole.EDITOR)) {
                memberRepository.findUserIdsByShareLinkId(link.getId()).forEach(collab::revokeAfterCommit);
            }
            memberRepository.updateRoleByShareLinkId(link.getId(), role);
        }
        link.setRole(role);
        return toDto(linkRepository.save(link));
    }

    @Transactional
    public void convertUnlisted(Project project) {
        if (linkRepository.findActiveForUpdate(project.getId()).isEmpty()) {
            ProjectShareLink link = newLink(project, project.getOwner());
            link.setRole(ProjectRole.VIEWER);
            linkRepository.save(link);
        }
        notificationService.recordShareLinkChanged(project.getOwner(), project);
    }

    @Transactional
    public void clear(Long projectId, User requester) {
        accessGuard.getOwnedProject(projectId, requester);
        linkRepository.findActiveForUpdate(projectId).ifPresent(this::revoke);
    }

    @Transactional
    public Project viewable(String token, User requester) {
        ProjectShareLink link = linkRepository.findByTokenHash(OpaqueToken.hash(token))
                .orElseThrow(() -> new ResourceNotFoundException("Share link not found"));
        assertUsable(link, requester, new Date());
        if (link.getRole() != ProjectRole.VIEWER) throw new ResourceNotFoundException("Share link not found");
        return link.getProject();
    }

    @Transactional
    public String accept(String token, User requester) {
        ProjectShareLink link = linkRepository.findByTokenHashForUpdate(OpaqueToken.hash(token))
                .orElseThrow(() -> new ResourceNotFoundException("Share link not found"));
        Project project = link.getProject();
        User owner = project.getOwner();
        Date now = new Date();
        assertUsable(link, requester, now);
        if (!owner.getId().equals(requester.getId())) {
            ProjectMember member = memberRepository.findByProjectIdAndUserId(project.getId(), requester.getId()).orElse(null);
            if (member == null) {
                memberRepository.save(memberService.newMember(project, requester, link.getRole(), null, link));
                notificationService.recordEvent(owner, NotificationTypes.MEMBER_JOINED, project, requester, now);
            } else if (member.getShareLink() != null && link.getRole().compareTo(member.getRole()) > 0) {
                member.setRole(link.getRole());
                member.setShareLink(link);
                memberRepository.save(member);
            }
            link.setUseCount(link.getUseCount() + 1);
            link.setLastUsedAt(now);
        }
        return codec.encode(project.getId());
    }

    private ProjectShareLink newLink(Project project, User createdBy) {
        String token = OpaqueToken.generate();
        ProjectShareLink link = new ProjectShareLink();
        link.setProject(project);
        link.setTokenHash(OpaqueToken.hash(token));
        link.setTokenCiphertext(cipher.convertToDatabaseColumn(token));
        link.setCreatedBy(createdBy);
        link.setCreatedAt(new Date());
        return link;
    }

    private void assertUsable(ProjectShareLink link, User requester, Date now) {
        User owner = link.getProject().getOwner();
        if (link.getRevokedAt() != null || (link.getExpiresAt() != null && !link.getExpiresAt().after(now)) || owner.isBanned()
                || (requester != null && blockedUserRepository.existsEitherDirection(owner.getId(), requester.getId()))) {
            throw new ResourceNotFoundException("Share link not found");
        }
    }

    private void revoke(ProjectShareLink link) {
        List<Long> userIds = memberRepository.findUserIdsByShareLinkId(link.getId());
        memberRepository.deleteByShareLinkId(link.getId());
        link.setRevokedAt(new Date());
        linkRepository.saveAndFlush(link);
        memberService.notifyRevoked(userIds, link.getProject().getId());
    }

    private ShareLinkDTO toDto(ProjectShareLink link) {
        return new ShareLinkDTO(link.getRole().name(), link.getCreatedAt(), link.getLastUsedAt(), link.getUseCount(),
                cipher.convertToEntityAttribute(link.getTokenCiphertext()));
    }
}
