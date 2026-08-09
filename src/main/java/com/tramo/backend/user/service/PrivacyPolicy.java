package com.tramo.backend.user.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.BlockedUserRepository;
import com.tramo.backend.user.repository.FollowRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class PrivacyPolicy {
    private final FollowRepository followRepository;
    private final BlockedUserRepository blockedUserRepository;

    public PrivacyPolicy(FollowRepository followRepository, BlockedUserRepository blockedUserRepository) {
        this.followRepository = followRepository;
        this.blockedUserRepository = blockedUserRepository;
    }

    public boolean blockedBy(User owner, User viewer) {
        if (viewer == null || owner.getId().equals(viewer.getId())) {
            return false;
        }
        return blockedUserRepository.existsByBlockerIdAndBlockedId(owner.getId(), viewer.getId());
    }

    public Set<Long> blockRelatedIds(User viewer) {
        if (viewer == null) {
            return Set.of();
        }
        List<Long> ids = blockedUserRepository.findRelatedUserIds(viewer.getId());
        return ids.isEmpty() ? Set.of() : Set.copyOf(ids);
    }

    public boolean isProfileViewable(User target, User requester) {
        if (blockedBy(target, requester)) {
            return false;
        }
        if (!Boolean.FALSE.equals(target.getVisibility())) {
            return true;
        }
        if (requester == null) {
            return false;
        }
        if (requester.getId().equals(target.getId()) || requester.getRole() == Role.ADMIN) {
            return true;
        }
        return followsBack(target, requester);
    }

    public void assertProjectViewable(Project project, User requester) {
        ProjectVisibility visibility = project.getVisibility();
        if (visibility != ProjectVisibility.UNLISTED && visibility != ProjectVisibility.PUBLISHED) {
            throw new ResourceNotFoundException("Project not found");
        }
        boolean isOwner = requester != null && project.getOwner().getId().equals(requester.getId());
        if (isOwner) {
            return;
        }
        if (project.getOwner().isBanned() || blockedBy(project.getOwner(), requester)) {
            throw new ResourceNotFoundException("Project not found");
        }
    }

    public boolean canComment(User owner, User requester) {
        if (requester == null) {
            return false;
        }
        if (requester.getId().equals(owner.getId())) {
            return true;
        }
        String policy = owner.getCommentsPolicy() != null ? owner.getCommentsPolicy() : "everyone";
        return switch (policy) {
            case "noone" -> false;
            case "following" -> followsBack(owner, requester);
            default -> true;
        };
    }

    public boolean canFork(User owner) {
        return !Boolean.FALSE.equals(owner.getAllowForks());
    }

    private boolean followsBack(User owner, User requester) {
        return followRepository.findByFollowerIdAndFollowedId(owner.getId(), requester.getId()).isPresent();
    }
}
