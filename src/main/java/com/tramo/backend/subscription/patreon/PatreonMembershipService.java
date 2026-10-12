package com.tramo.backend.subscription.patreon;

import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.subscription.dto.SubscriptionStatusDTO;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.user.entity.User;
import org.springframework.stereotype.Service;

@Service
public class PatreonMembershipService {
    private final PatreonClient patreonClient;
    private final SubscriptionService subscriptionService;

    public PatreonMembershipService(PatreonClient patreonClient, SubscriptionService subscriptionService) {
        this.patreonClient = patreonClient;
        this.subscriptionService = subscriptionService;
    }

    public SubscriptionStatusDTO sync(User user) {
        if (user.getPatreonUserId() == null || user.getPatreonAccessToken() == null) {
            return subscriptionService.getStatus(user);
        }
        PatreonClient.PatreonIdentity identity = patreonClient.fetchIdentity(user.getPatreonAccessToken());
        if (!user.getPatreonUserId().equals(identity.patreonUserId())) {
            throw new InvalidTokenException("Patreon account does not match the linked account");
        }
        if (identity.activePatron()) {
            subscriptionService.activateSupporterSubscription(user, subscriptionService.findOrCreateSupporterPlan());
        } else {
            subscriptionService.deactivateSupporterSubscription(user);
        }
        return subscriptionService.getStatus(user);
    }
}
