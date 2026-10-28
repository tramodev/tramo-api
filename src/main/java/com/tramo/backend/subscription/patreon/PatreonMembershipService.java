package com.tramo.backend.subscription.patreon;

import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import com.tramo.backend.security.crypto.EncryptedStringConverter;
import com.tramo.backend.subscription.dto.SubscriptionStatusDTO;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.user.entity.User;
import org.springframework.stereotype.Service;

@Service
public class PatreonMembershipService {
    private final PatreonClient patreonClient;
    private final SubscriptionService subscriptionService;
    private final EncryptedStringConverter cipher;

    public PatreonMembershipService(PatreonClient patreonClient, SubscriptionService subscriptionService, EncryptedStringConverter cipher) {
        this.patreonClient = patreonClient;
        this.subscriptionService = subscriptionService;
        this.cipher = cipher;
    }

    public SubscriptionStatusDTO sync(User user) {
        if (user.getPatreonUserId() == null || user.getEncryptedPatreonAccessToken() == null) {
            return subscriptionService.getStatus(user);
        }
        apply(user, fetchIdentity(user));
        return subscriptionService.getStatus(user);
    }

    public PatreonClient.PatreonIdentity fetchIdentity(User user) {
        String accessToken;
        try {
            accessToken = cipher.convertToEntityAttribute(user.getEncryptedPatreonAccessToken());
        } catch (IllegalStateException failure) {
            throw new RequestValidationException(RequestErrorCode.PATREON_RECONNECT_REQUIRED, failure);
        }
        PatreonClient.PatreonIdentity identity = patreonClient.fetchIdentity(accessToken);
        if (!user.getPatreonUserId().equals(identity.patreonUserId())) {
            throw new InvalidTokenException("Patreon account does not match the linked account");
        }
        return identity;
    }

    public void apply(User user, PatreonClient.PatreonIdentity identity) {
        if (!identity.patreonUserId().equals(user.getPatreonUserId())) {
            throw new InvalidTokenException("Patreon account does not match the linked account");
        }
        if (identity.activePatron()) {
            subscriptionService.activateSupporterSubscription(user, subscriptionService.findOrCreateSupporterPlan());
        } else {
            subscriptionService.deactivateSupporterSubscription(user);
        }
    }
}
