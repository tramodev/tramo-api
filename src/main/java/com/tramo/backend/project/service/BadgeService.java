package com.tramo.backend.project.service;

import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.dto.BadgeDTO;
import com.tramo.backend.project.dto.ProfileStatsDTO;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectVoteRepository;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.entity.UserBadge;
import com.tramo.backend.user.repository.FollowRepository;
import com.tramo.backend.user.repository.UserBadgeRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BadgeService {
    static final long ON_THE_MAP_VIEW_THRESHOLD = 1000;
    static final long TRENDSETTER_VIEW_THRESHOLD = 10000;
    static final List<String> VIEW_BADGE_CODES = List.of("on_the_map", "trendsetter");

    private final ProjectRepository projectRepository;
    private final ProjectVoteRepository projectVoteRepository;
    private final FollowRepository followRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final SubscriptionService subscriptionService;
    private final NotificationService notificationService;

    public BadgeService(ProjectRepository projectRepository, ProjectVoteRepository projectVoteRepository,
                         FollowRepository followRepository, UserBadgeRepository userBadgeRepository,
                         SubscriptionService subscriptionService, NotificationService notificationService) {
        this.projectRepository = projectRepository;
        this.projectVoteRepository = projectVoteRepository;
        this.followRepository = followRepository;
        this.userBadgeRepository = userBadgeRepository;
        this.subscriptionService = subscriptionService;
        this.notificationService = notificationService;
    }

    ProfileStatsDTO getProfileStats(User user) {
        long trailsPublished = projectRepository.countByOwnerIdAndVisibility(user.getId(), ProjectVisibility.PUBLISHED);
        long upvotesReceived = projectVoteRepository.countByProjectOwnerIdAndProjectPublished(user.getId());
        long totalViews = projectRepository.sumViewCountByOwnerIdAndPublished(user.getId());
        long forksCount = projectRepository.countByOwnerIdAndForkedFromNotNull(user.getId());
        long followersCount = followRepository.countByFollowedId(user.getId());
        long followingCount = followRepository.countByFollowerId(user.getId());
        return new ProfileStatsDTO(trailsPublished, upvotesReceived, totalViews, forksCount, followersCount, followingCount);
    }

    List<BadgeDTO> buildBadges(ProfileStatsDTO stats, boolean supporter) {
        List<BadgeDTO> badges = new ArrayList<>();
        badges.add(badge(SubscriptionService.SUPPORTER_BADGE_CODE, "Supporter", "Support Tramo with a subscription", supporter ? 1 : 0, 1));
        badges.add(badge("first_publish", "First Publish", "Publish your first trail", stats.getTrailsPublished(), 1));
        badges.add(badge("prolific", "Prolific", "Publish 10 trails", stats.getTrailsPublished(), 10));
        badges.add(badge("rising_star", "Rising Star", "Earn 10 upvotes", stats.getUpvotesReceived(), 10));
        badges.add(badge("crowd_favorite", "Crowd Favorite", "Earn 100 upvotes", stats.getUpvotesReceived(), 100));
        badges.add(badge("on_the_map", "On the Map", "Reach 1,000 total views", stats.getTotalViews(), ON_THE_MAP_VIEW_THRESHOLD));
        badges.add(badge("trendsetter", "Trendsetter", "Reach 10,000 total views", stats.getTotalViews(), TRENDSETTER_VIEW_THRESHOLD));
        badges.add(badge("forked_once", "Forked Once", "Get forked by another user", stats.getForksCount(), 1));
        badges.add(badge("remix_king", "Remix King", "Get forked 25 times", stats.getForksCount(), 25));
        return badges;
    }

    List<BadgeDTO> buildBadges(ProfileStatsDTO stats, User user) {
        return buildBadges(stats, subscriptionService.isSupporter(user));
    }

    private BadgeDTO badge(String code, String name, String description, long progress, long target) {
        return new BadgeDTO(code, name, description, progress >= target, Math.min(progress, target), target);
    }

    void awardNewlyEarnedBadges(User user, List<BadgeDTO> badges) {
        Set<String> alreadyAwarded = userBadgeRepository.findByUserId(user.getId()).stream()
                .map(UserBadge::getBadgeCode)
                .collect(Collectors.toSet());

        for (BadgeDTO badge : badges) {
            if (badge.isEarned() && !alreadyAwarded.contains(badge.getCode())) {
                UserBadge userBadge = new UserBadge();
                userBadge.setUser(user);
                userBadge.setBadgeCode(badge.getCode());
                userBadge.setEarnedAt(new Date());
                userBadgeRepository.save(userBadge);
                notificationService.recordBadge(user, badge.getCode(), badge.getName());
            }
        }
    }

    void checkAndAwardBadges(User user) {
        ProfileStatsDTO stats = getProfileStats(user);
        awardNewlyEarnedBadges(user, buildBadges(stats, subscriptionService.isSupporter(user)));
    }

    boolean crossedViewBadgeThreshold(long before, long after) {
        return (before < ON_THE_MAP_VIEW_THRESHOLD && after >= ON_THE_MAP_VIEW_THRESHOLD)
                || (before < TRENDSETTER_VIEW_THRESHOLD && after >= TRENDSETTER_VIEW_THRESHOLD);
    }
}
