package com.tramo.backend.project.service;

import com.tramo.backend.auth.service.MinAgeValidator;
import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.project.dto.BadgeDTO;
import com.tramo.backend.project.dto.ProfileStatsBundleDTO;
import com.tramo.backend.project.dto.ProfileStatsDTO;
import com.tramo.backend.project.dto.PublicProfileDTO;
import com.tramo.backend.project.dto.UpdateProfileRequestDTO;
import com.tramo.backend.project.dto.UserProfileDTO;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.upload.ImageDeletionQueue;
import com.tramo.backend.upload.R2Client;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.BlockedUserRepository;
import com.tramo.backend.user.repository.FollowRepository;
import com.tramo.backend.user.repository.UserBadgeRepository;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;

@Service
public class ProfileService {
    private final AccessGuard accessGuard;
    private final UserRepository userRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final FollowRepository followRepository;
    private final BlockedUserRepository blockedUserRepository;
    private final R2Client r2Client;
    private final SubscriptionService subscriptionService;
    private final MinAgeValidator minAgeValidator;
    private final ImageDeletionQueue imageDeletionQueue;
    private final BadgeService badgeService;

    public ProfileService(AccessGuard accessGuard, UserRepository userRepository,
                           UserBadgeRepository userBadgeRepository, FollowRepository followRepository,
                           BlockedUserRepository blockedUserRepository, R2Client r2Client,
                           SubscriptionService subscriptionService, MinAgeValidator minAgeValidator,
                           ImageDeletionQueue imageDeletionQueue, BadgeService badgeService) {
        this.accessGuard = accessGuard;
        this.userRepository = userRepository;
        this.userBadgeRepository = userBadgeRepository;
        this.followRepository = followRepository;
        this.blockedUserRepository = blockedUserRepository;
        this.r2Client = r2Client;
        this.subscriptionService = subscriptionService;
        this.minAgeValidator = minAgeValidator;
        this.imageDeletionQueue = imageDeletionQueue;
        this.badgeService = badgeService;
    }

    public UserProfileDTO getProfile(User user) {
        User fresh = userRepository.findById(user.getId()).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return new UserProfileDTO(fresh.getUsername(), fresh.getEmail(), fresh.getBio(), fresh.getBirthDate(), fresh.getLocation(), fresh.getWebsite(), fresh.getImageUrl(), fresh.getBannerUrl(), fresh.getCreatedAt(), fresh.getRole().name(), fresh.getSelectedBadge());
    }

    private Integer computeAge(LocalDate birthDate) {
        return birthDate != null ? Period.between(birthDate, LocalDate.now()).getYears() : null;
    }

    @Transactional
    public UserProfileDTO updateProfile(User principal, UpdateProfileRequestDTO request) {
        User user = userRepository.findById(principal.getId()).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        String previousImageUrl = user.getImageUrl();
        String previousBannerUrl = user.getBannerUrl();
        if (request.getBio() != null) {
            user.setBio(request.getBio().isBlank() ? null : request.getBio());
        }
        if (request.getBirthDate() != null && user.getBirthDate() == null) {
            minAgeValidator.validate(request.getBirthDate());
            user.setBirthDate(request.getBirthDate());
        }
        if (request.getLocation() != null) {
            user.setLocation(request.getLocation().isBlank() ? null : request.getLocation());
        }
        if (request.getWebsite() != null) {
            user.setWebsite(request.getWebsite().isBlank() ? null : request.getWebsite());
        }
        if (request.getImageUrl() != null) {
            String newImageUrl = request.getImageUrl().isBlank() ? null : request.getImageUrl();
            if (newImageUrl != null && !r2Client.isOwnedUrl(newImageUrl, "avatar", user.getId())) {
                throw new IllegalArgumentException("Invalid image URL");
            }
            user.setImageUrl(newImageUrl);
        }
        if (request.getBannerUrl() != null) {
            String newBannerUrl = request.getBannerUrl().isBlank() ? null : request.getBannerUrl();
            if (newBannerUrl != null) {
                if (!r2Client.isOwnedUrl(newBannerUrl, "banner", user.getId())) {
                    throw new IllegalArgumentException("Invalid banner URL");
                }
                if (!subscriptionService.isSupporter(user)) {
                    throw new LimitExceededException("Profile banners are a supporter perk. Upgrade to use one.");
                }
            }
            user.setBannerUrl(newBannerUrl);
        }
        if (request.getSelectedBadge() != null) {
            String badgeCode = request.getSelectedBadge().isBlank() ? null : request.getSelectedBadge();
            if (badgeCode != null) {
                boolean earned = userBadgeRepository.findByUserId(user.getId()).stream()
                        .anyMatch(ub -> ub.getBadgeCode().equals(badgeCode));
                if (!earned) {
                    throw new IllegalArgumentException("Badge not earned: " + badgeCode);
                }
            }
            user.setSelectedBadge(badgeCode);
        }
        User saved = userRepository.save(user);
        if (request.getImageUrl() != null && !request.getImageUrl().equals(previousImageUrl)) {
            imageDeletionQueue.queue(previousImageUrl, user.getId());
        }
        if (request.getBannerUrl() != null && !request.getBannerUrl().equals(previousBannerUrl)) {
            imageDeletionQueue.queue(previousBannerUrl, user.getId());
        }
        return new UserProfileDTO(saved.getUsername(), saved.getEmail(), saved.getBio(), saved.getBirthDate(), saved.getLocation(), saved.getWebsite(), saved.getImageUrl(), saved.getBannerUrl(), saved.getCreatedAt(), saved.getRole().name(), saved.getSelectedBadge());
    }

    public ProfileStatsBundleDTO getProfileStatsBundle(User user) {
        ProfileStatsDTO stats = badgeService.getProfileStats(user);
        List<BadgeDTO> badges = badgeService.buildBadges(stats, subscriptionService.isSupporter(user));
        badgeService.awardNewlyEarnedBadges(user, badges);
        return new ProfileStatsBundleDTO(stats, badges);
    }

    public PublicProfileDTO getPublicProfile(String username, User requester) {
        User target = accessGuard.publicProfileTarget(username, requester);

        ProfileStatsDTO stats = badgeService.getProfileStats(target);
        boolean self = requester != null && requester.getId().equals(target.getId());
        boolean following = !self && requester != null
                && followRepository.findByFollowerIdAndFollowedId(requester.getId(), target.getId()).isPresent();
        boolean blocked = !self && requester != null
                && blockedUserRepository.findByBlockerIdAndBlockedId(requester.getId(), target.getId()).isPresent();

        Integer publicAge = Boolean.FALSE.equals(target.getShowAge()) ? null : computeAge(target.getBirthDate());
        return new PublicProfileDTO(target.getUsername(), target.getBio(), publicAge, target.getLocation(), target.getWebsite(), target.getImageUrl(), target.getBannerUrl(), target.getCreatedAt(),
                stats, badgeService.buildBadges(stats, subscriptionService.isSupporter(target)), target.getSelectedBadge(), following, self, blocked,
                target.getShowUpvotes() == null || target.getShowUpvotes());
    }
}
