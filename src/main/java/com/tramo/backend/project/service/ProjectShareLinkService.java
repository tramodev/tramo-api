// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.auth.service.OpaqueToken;
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

    public ProjectShareLinkService(AccessGuard accessGuard, ProjectShareLinkRepository linkRepository,
                                   ProjectMemberRepository memberRepository, ProjectMemberService memberService,
                                   BlockedUserRepository blockedUserRepository, NotificationService notificationService,
                                   ProjectIdCodec codec) {
        this.accessGuard = accessGuard;
        this.linkRepository = linkRepository;
        this.memberRepository = memberRepository;
        this.memberService = memberService;
        this.blockedUserRepository = blockedUserRepository;
        this.notificationService = notificationService;
        this.codec = codec;
    }

    public List<ShareLinkDTO> list(Long projectId, User requester) {
        accessGuard.getOwnedProject(projectId, requester);
        return linkRepository.findByProjectIdAndRevokedAtIsNullOrderByCreatedAtAsc(projectId).stream()
                .map(link -> toDto(link, null)).toList();
    }

    @Transactional
    public ShareLinkDTO create(Long projectId, ProjectRole role, Date expiresAt, User requester) {
        Project project = accessGuard.getOwnedProject(projectId, requester);
        linkRepository.findActiveForUpdate(projectId, role).ifPresent(this::revoke);
        String token = OpaqueToken.generate();
        ProjectShareLink link = new ProjectShareLink();
        link.setProject(project);
        link.setTokenHash(OpaqueToken.hash(token));
        link.setRole(role);
        link.setCreatedBy(requester);
        link.setCreatedAt(new Date());
        link.setExpiresAt(expiresAt);
        return toDto(linkRepository.save(link), token);
    }

    @Transactional
    public void revoke(Long projectId, Long linkId, User requester) {
        accessGuard.getOwnedProject(projectId, requester);
        ProjectShareLink link = linkRepository.findByIdAndProjectIdForUpdate(linkId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Share link not found"));
        if (link.getRevokedAt() == null) revoke(link);
    }

    @Transactional
    public String accept(String token, User requester) {
        ProjectShareLink link = linkRepository.findByTokenHashForUpdate(OpaqueToken.hash(token))
                .orElseThrow(() -> new ResourceNotFoundException("Share link not found"));
        Project project = link.getProject();
        User owner = project.getOwner();
        Date now = new Date();
        if (link.getRevokedAt() != null || (link.getExpiresAt() != null && !link.getExpiresAt().after(now))
                || owner.isBanned() || blockedUserRepository.existsEitherDirection(owner.getId(), requester.getId())) {
            throw new ResourceNotFoundException("Share link not found");
        }
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

    private void revoke(ProjectShareLink link) {
        List<Long> userIds = memberRepository.findUserIdsByShareLinkId(link.getId());
        memberRepository.deleteByShareLinkId(link.getId());
        link.setRevokedAt(new Date());
        linkRepository.saveAndFlush(link);
        memberService.notifyRevoked(userIds, link.getProject().getId());
    }

    private ShareLinkDTO toDto(ProjectShareLink link, String token) {
        return new ShareLinkDTO(link.getId(), link.getRole().name(), link.getCreatedAt(), link.getExpiresAt(),
                link.getLastUsedAt(), link.getUseCount(), token);
    }
}
