package com.tramo.backend.user.service;

import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.FollowRepository;
import org.springframework.stereotype.Component;

@Component
public class PrivacyPolicy {
    private final FollowRepository followRepository;

    public PrivacyPolicy(FollowRepository followRepository) {
        this.followRepository = followRepository;
    }

    public boolean isProfileViewable(User target, User requester) {
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
