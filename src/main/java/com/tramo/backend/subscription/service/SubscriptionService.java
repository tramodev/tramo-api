package com.tramo.backend.subscription.service;

import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.subscription.dto.SubscriptionStatusDTO;
import com.tramo.backend.subscription.entity.Subscription;
import com.tramo.backend.subscription.repository.SubscriptionRepository;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.entity.UserBadge;
import com.tramo.backend.user.repository.UserBadgeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
public class SubscriptionService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CANCELED = "CANCELED";
    public static final String SUPPORTER_BADGE_CODE = "supporter";
    private static final long WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private final SubscriptionRepository subscriptionRepository;
    private final UploadRecordRepository uploadRecordRepository;
    private final ProjectRepository projectRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final NotificationService notificationService;
    private final long freeStorageBytes;
    private final long premiumStorageBytes;
    private final long freePublishesPerWeek;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               UploadRecordRepository uploadRecordRepository,
                               ProjectRepository projectRepository,
                               UserBadgeRepository userBadgeRepository,
                               NotificationService notificationService,
                               @Value("${app.limits.free.storage-bytes}") long freeStorageBytes,
                               @Value("${app.limits.premium.storage-bytes}") long premiumStorageBytes,
                               @Value("${app.limits.free.publishes-per-week}") long freePublishesPerWeek) {
        this.subscriptionRepository = subscriptionRepository;
        this.uploadRecordRepository = uploadRecordRepository;
        this.projectRepository = projectRepository;
        this.userBadgeRepository = userBadgeRepository;
        this.notificationService = notificationService;
        this.freeStorageBytes = freeStorageBytes;
        this.premiumStorageBytes = premiumStorageBytes;
        this.freePublishesPerWeek = freePublishesPerWeek;
    }

    public boolean isPremium(User user) {
        return isPremium(user.getId());
    }

    public boolean isPremium(Long userId) {
        return subscriptionRepository.findFirstByUserIdAndStatus(userId, STATUS_ACTIVE)
                .filter(s -> s.getEndDate() == null || s.getEndDate().after(new Date()))
                .isPresent();
    }

    public long storageQuotaBytes(User user) {
        return isPremium(user) ? premiumStorageBytes : freeStorageBytes;
    }

    /** Blocks new uploads past quota; never touches existing content. */
    public void assertUploadAllowed(User user, long contentBytes) {
        long used = uploadRecordRepository.sumBytesByUserId(user.getId());
        long quota = storageQuotaBytes(user);
        if (used + contentBytes > quota) {
            throw new LimitExceededException(
                    "Storage limit reached (%dMB). Free up space or upgrade for more.".formatted(quota / (1024 * 1024)));
        }
    }

    /** First-publish rate limit — republishing an already-published-once project is exempt by design. */
    public void assertCanPublish(User user) {
        if (isPremium(user)) {
            return;
        }
        Date weekAgo = new Date(System.currentTimeMillis() - WEEK_MILLIS);
        long recent = projectRepository.countByOwnerIdAndFirstPublishedDateAfter(user.getId(), weekAgo);
        if (recent >= freePublishesPerWeek) {
            throw new LimitExceededException(
                    "Publish limit reached (%d per week on the free plan). Try again later or upgrade.".formatted(freePublishesPerWeek));
        }
    }

    public SubscriptionStatusDTO getStatus(User user) {
        boolean premium = isPremium(user);
        long used = uploadRecordRepository.sumBytesByUserId(user.getId());
        Date weekAgo = new Date(System.currentTimeMillis() - WEEK_MILLIS);
        long publishesUsed = projectRepository.countByOwnerIdAndFirstPublishedDateAfter(user.getId(), weekAgo);
        return new SubscriptionStatusDTO(
                premium,
                used,
                premium ? premiumStorageBytes : freeStorageBytes,
                publishesUsed,
                premium ? -1 : freePublishesPerWeek);
    }

    // Mock until a real payment provider lands: "upgrading" just creates the ACTIVE row.
    // Only this method (and cancel) should change when payments become real.
    @Transactional
    public SubscriptionStatusDTO mockUpgrade(User user) {
        if (!isPremium(user)) {
            Subscription subscription = new Subscription();
            subscription.setUser(user);
            subscription.setStatus(STATUS_ACTIVE);
            subscription.setStartDate(new Date());
            subscriptionRepository.save(subscription);
            awardSupporterBadge(user);
        }
        return getStatus(user);
    }

    @Transactional
    public SubscriptionStatusDTO cancel(User user) {
        subscriptionRepository.findFirstByUserIdAndStatus(user.getId(), STATUS_ACTIVE)
                .ifPresent(subscription -> {
                    subscription.setStatus(STATUS_CANCELED);
                    subscription.setEndDate(new Date());
                    subscriptionRepository.save(subscription);
                });
        return getStatus(user);
    }

    private void awardSupporterBadge(User user) {
        boolean alreadyAwarded = userBadgeRepository.findByUserId(user.getId()).stream()
                .anyMatch(b -> SUPPORTER_BADGE_CODE.equals(b.getBadgeCode()));
        if (alreadyAwarded) {
            return;
        }
        UserBadge badge = new UserBadge();
        badge.setUser(user);
        badge.setBadgeCode(SUPPORTER_BADGE_CODE);
        badge.setEarnedAt(new Date());
        userBadgeRepository.save(badge);
        notificationService.recordBadge(user, SUPPORTER_BADGE_CODE, "Supporter");
    }
}
