// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.user.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.dto.PageResponseDTO;
import com.tramo.backend.user.dto.BlockResponseDTO;
import com.tramo.backend.user.dto.BlockedUserDTO;
import com.tramo.backend.user.entity.BlockedUser;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.BlockedUserRepository;
import com.tramo.backend.user.repository.FollowRepository;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import com.tramo.backend.common.Pagination;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class BlockedUserService {
    private final UserRepository userRepository;
    private final BlockedUserRepository blockedUserRepository;
    private final FollowRepository followRepository;
    private final NotificationService notificationService;

    public BlockedUserService(UserRepository userRepository, BlockedUserRepository blockedUserRepository,
                               FollowRepository followRepository, NotificationService notificationService) {
        this.userRepository = userRepository;
        this.blockedUserRepository = blockedUserRepository;
        this.followRepository = followRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public BlockResponseDTO toggleBlock(User blocker, String targetUsername) {
        User target = userRepository.findByUsernameIgnoreCase(targetUsername)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (target.getId().equals(blocker.getId())) {
            throw new AccessDeniedException("Cannot block yourself");
        }

        var existing = blockedUserRepository.findByBlockerIdAndBlockedId(blocker.getId(), target.getId());
        boolean blocked;
        if (existing.isPresent()) {
            blockedUserRepository.delete(existing.get());
            blocked = false;
        } else {
            BlockedUser block = new BlockedUser();
            block.setBlocker(blocker);
            block.setBlocked(target);
            block.setCreatedDate(new Date());
            blockedUserRepository.save(block);
            blocked = true;
            followRepository.findByFollowerIdAndFollowedId(blocker.getId(), target.getId()).ifPresent(followRepository::delete);
            followRepository.findByFollowerIdAndFollowedId(target.getId(), blocker.getId()).ifPresent(followRepository::delete);
        }
        notificationService.publishUnreadCount(blocker);
        notificationService.publishUnreadCount(target);
        return new BlockResponseDTO(blocked);
    }

    public PageResponseDTO<BlockedUserDTO> list(User blocker, int page, int size) {
        Page<BlockedUser> result = blockedUserRepository.findByBlockerIdOrderByCreatedDateDesc(blocker.getId(), Pagination.page(page, size));
        var items = result.getContent().stream()
                .map(b -> new BlockedUserDTO(b.getBlocked().getUsername(), b.getBlocked().getImageUrl(), b.getBlocked().getBio()))
                .toList();
        return new PageResponseDTO<>(items, result.hasNext());
    }
}
