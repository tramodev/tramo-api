package com.tramo.backend.project.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.dto.FollowResponseDTO;
import com.tramo.backend.project.dto.FollowUserDTO;
import com.tramo.backend.project.dto.PageResponseDTO;
import com.tramo.backend.user.entity.Follow;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.BlockedUserRepository;
import com.tramo.backend.user.repository.FollowRepository;
import com.tramo.backend.user.repository.UserRepository;
import com.tramo.backend.user.service.PrivacyPolicy;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Set;

@Service
public class FollowService {
    private final AccessGuard accessGuard;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final BlockedUserRepository blockedUserRepository;
    private final NotificationService notificationService;
    private final PrivacyPolicy privacyPolicy;

    public FollowService(AccessGuard accessGuard, UserRepository userRepository, FollowRepository followRepository,
                          BlockedUserRepository blockedUserRepository, NotificationService notificationService,
                          PrivacyPolicy privacyPolicy) {
        this.accessGuard = accessGuard;
        this.userRepository = userRepository;
        this.followRepository = followRepository;
        this.blockedUserRepository = blockedUserRepository;
        this.notificationService = notificationService;
        this.privacyPolicy = privacyPolicy;
    }

    @Transactional
    public FollowResponseDTO toggleFollow(String username, User requester) {
        User target = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (target.getId().equals(requester.getId())) {
            throw new AccessDeniedException("Cannot follow yourself");
        }
        if (blockedUserRepository.existsEitherDirection(requester.getId(), target.getId())) {
            throw new AccessDeniedException("Cannot follow this user");
        }

        var existing = followRepository.findByFollowerIdAndFollowedId(requester.getId(), target.getId());
        boolean following;
        if (existing.isPresent()) {
            followRepository.delete(existing.get());
            following = false;
        } else {
            Follow follow = new Follow();
            follow.setFollower(requester);
            follow.setFollowed(target);
            follow.setCreatedDate(new Date());
            followRepository.save(follow);
            following = true;
            notificationService.recordEvent(target, "FOLLOW", null, requester);
        }
        return new FollowResponseDTO(following, followRepository.countByFollowedId(target.getId()));
    }

    public PageResponseDTO<FollowUserDTO> getFollowers(String username, User requester, int page, int size) {
        User target = accessGuard.publicProfileTarget(username, requester);
        Page<Follow> result = followRepository.findByFollowedIdOrderByCreatedDateDesc(target.getId(), PageRequest.of(page, size));
        List<User> users = result.getContent().stream().map(Follow::getFollower).toList();
        return toFollowUserPage(users, requester, result.hasNext());
    }

    public PageResponseDTO<FollowUserDTO> getFollowing(String username, User requester, int page, int size) {
        User target = accessGuard.publicProfileTarget(username, requester);
        Page<Follow> result = followRepository.findByFollowerIdOrderByCreatedDateDesc(target.getId(), PageRequest.of(page, size));
        List<User> users = result.getContent().stream().map(Follow::getFollowed).toList();
        return toFollowUserPage(users, requester, result.hasNext());
    }

    private PageResponseDTO<FollowUserDTO> toFollowUserPage(List<User> allUsers, User requester, boolean hasMore) {
        Set<Long> blockRelated = privacyPolicy.blockRelatedIds(requester);
        List<User> users = blockRelated.isEmpty()
                ? allUsers
                : allUsers.stream().filter(u -> !blockRelated.contains(u.getId())).toList();
        List<Long> ids = users.stream().map(User::getId).toList();
        Set<Long> followingIds = requester == null || ids.isEmpty()
                ? Set.of()
                : Set.copyOf(followRepository.findFollowedIdsIn(requester.getId(), ids));
        List<FollowUserDTO> items = users.stream()
                .map(u -> new FollowUserDTO(u.getUsername(), u.getImageUrl(), u.getBio(), followingIds.contains(u.getId())))
                .toList();
        return new PageResponseDTO<>(items, hasMore);
    }
}
