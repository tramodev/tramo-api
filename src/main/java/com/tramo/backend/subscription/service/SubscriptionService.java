// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.subscription.service;

import com.tramo.backend.exception.StorageQuotaExceededException;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.subscription.dto.SubscriptionStatusDTO;
import com.tramo.backend.subscription.entity.Plan;
import com.tramo.backend.subscription.entity.Subscription;
import com.tramo.backend.subscription.repository.PlanRepository;
import com.tramo.backend.subscription.repository.SubscriptionRepository;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserBadgeRepository;
import com.tramo.backend.user.repository.UserRepository;
import com.tramo.backend.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
public class SubscriptionService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CANCELED = "CANCELED";
    public static final String SUPPORTER_BADGE_CODE = "supporter";
    private static final String PATREON_PLAN_NAME = "Patreon Supporter";

    private final SubscriptionRepository subscriptionRepository;
    private final UploadRecordRepository uploadRecordRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final NotificationService notificationService;
    private final PlanRepository planRepository;
    private final UserRepository userRepository;
    private final long freeStorageBytes;
    private final long supporterStorageBytes;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               UploadRecordRepository uploadRecordRepository,
                               UserBadgeRepository userBadgeRepository,
                               NotificationService notificationService,
                                PlanRepository planRepository,
                                UserRepository userRepository,
                               @Value("${app.limits.free.storage-bytes}") long freeStorageBytes,
                               @Value("${app.limits.supporter.storage-bytes}") long supporterStorageBytes) {
        this.subscriptionRepository = subscriptionRepository;
        this.uploadRecordRepository = uploadRecordRepository;
        this.userBadgeRepository = userBadgeRepository;
        this.notificationService = notificationService;
        this.planRepository = planRepository;
        this.userRepository = userRepository;
        this.freeStorageBytes = freeStorageBytes;
        this.supporterStorageBytes = supporterStorageBytes;
    }

    public boolean isSupporter(User user) {
        return isSupporter(user.getId());
    }

    public boolean isSupporter(Long userId) {
        return subscriptionRepository.findFirstByUserIdAndStatus(userId, STATUS_ACTIVE)
                .filter(s -> s.getEndDate() == null || s.getEndDate().after(new Date()))
                .isPresent();
    }

    public void assertUploadAllowed(User user, long contentBytes, boolean supporter) {
        if (contentBytes > remainingStorageBytes(user, supporter)) {
            throw new StorageQuotaExceededException();
        }
    }

    public long remainingStorageBytes(User user, boolean supporter) {
        long used = uploadRecordRepository.sumStorageBytesByUserId(user.getId());
        return (supporter ? supporterStorageBytes : freeStorageBytes) - used;
    }

    public void lockStorage(User user) {
        userRepository.lockStorageById(user.getId()).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    public SubscriptionStatusDTO getStatus(User user) {
        boolean supporter = isSupporter(user);
        var usage = uploadRecordRepository.getStorageUsageByUserId(user.getId());
        return new SubscriptionStatusDTO(
                supporter,
                usage.getImageBytes() + usage.getTextBytes(),
                usage.getImageBytes(),
                usage.getTextBytes(),
                supporter ? supporterStorageBytes : freeStorageBytes,
                -1);
    }

    
    
    @Transactional
    public Plan findOrCreateSupporterPlan() {
        return planRepository.findByName(PATREON_PLAN_NAME)
                .orElseGet(() -> {
                    Plan plan = new Plan();
                    plan.setName(PATREON_PLAN_NAME);
                    plan.setActive(true);
                    return planRepository.save(plan);
                });
    }

    
    
    @Transactional
    public void activateSupporterSubscription(User user, Plan plan) {
        userRepository.findByIdForUpdate(user.getId()).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (isSupporter(user)) {
            return;
        }
        subscriptionRepository.findFirstByUserIdAndStatus(user.getId(), STATUS_ACTIVE)
                .ifPresent(expired -> {
                    expired.setStatus(STATUS_CANCELED);
                    subscriptionRepository.saveAndFlush(expired);
                });
        Subscription subscription = new Subscription();
        subscription.setUser(user);
        subscription.setStatus(STATUS_ACTIVE);
        subscription.setStartDate(new Date());
        subscription.setPlan(plan);
        subscriptionRepository.save(subscription);
        awardSupporterBadge(user);
    }

    @Transactional
    public void deactivateSupporterSubscription(User user) {
        userRepository.findByIdForUpdate(user.getId()).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        subscriptionRepository.findFirstByUserIdAndStatus(user.getId(), STATUS_ACTIVE)
                .ifPresent(subscription -> {
                    subscription.setStatus(STATUS_CANCELED);
                    subscription.setEndDate(new Date());
                    subscriptionRepository.save(subscription);
                });
    }

    @Transactional
    public SubscriptionStatusDTO cancel(User user) {
        deactivateSupporterSubscription(user);
        return getStatus(user);
    }

    private void awardSupporterBadge(User user) {
        if (userBadgeRepository.insertIfAbsent(user.getId(), SUPPORTER_BADGE_CODE) == 1) {
            notificationService.recordBadge(user, SUPPORTER_BADGE_CODE, "Supporter");
        }
    }
}
