// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.subscription.patreon;

import com.tramo.backend.common.SafeLog;
import com.tramo.backend.security.crypto.EncryptedStringConverter;
import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;



@RestController
@RequestMapping("/api/auth/patreon")
public class PatreonController {
    private static final Logger log = LoggerFactory.getLogger(PatreonController.class);

    private final PatreonConnectTokenRepository connectTokenRepository;
    private final PatreonClient patreonClient;
    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final String clientId;
    private final String redirectUri;
    private final String frontendUrl;
    private final EncryptedStringConverter cipher;

    public PatreonController(PlatformTransactionManager transactionManager,
                              PatreonConnectTokenRepository connectTokenRepository,
                              PatreonClient patreonClient,
                              SubscriptionService subscriptionService,
                              UserRepository userRepository,
                              @Value("${app.patreon.client-id}") String clientId,
                              @Value("${app.patreon.redirect-uri}") String redirectUri,
                              @Value("${app.frontend-url}") String frontendUrl, EncryptedStringConverter cipher) {
        this.connectTokenRepository = connectTokenRepository;
        this.patreonClient = patreonClient;
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.frontendUrl = frontendUrl;
        this.cipher = cipher;
    }

    @GetMapping("/connect")
    @Transactional
    public Map<String, String> connect(@AuthenticationPrincipal User user) {
        connectTokenRepository.deleteByUserId(user.getId());
        PatreonConnectToken connectToken = new PatreonConnectToken();
        connectToken.setUser(user);
        connectToken.setToken(UUID.randomUUID().toString());
        connectToken.setExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES));
        connectTokenRepository.save(connectToken);

        String authorizeUrl = UriComponentsBuilder.fromUriString("https://www.patreon.com/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "identity identity.memberships")
                .queryParam("state", connectToken.getToken())
                .build()
                .toUriString();
        return Map.of("authorizeUrl", authorizeUrl);
    }

    
    
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        URI location = UriComponentsBuilder.fromUriString(frontendUrl + "/api/auth/patreon/callback")
                .queryParamIfPresent("code", java.util.Optional.ofNullable(code))
                .queryParamIfPresent("state", java.util.Optional.ofNullable(state))
                .queryParamIfPresent("error", java.util.Optional.ofNullable(error))
                .build().encode().toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }

    @GetMapping("/complete")
    public ResponseEntity<Void> complete(@AuthenticationPrincipal User principal, @RequestParam(required = false) String code,
                                          @RequestParam(required = false) String state,
                                          @RequestParam(required = false) String error,
                                          @RequestParam(name = "error_description", required = false) String errorDescription) {
        if (error != null) {
            return rejected("OAUTH_DENIED", null);
        }
        if (code == null || state == null) {
            return rejected("OAUTH_PARAMETERS_MISSING", null);
        }

        if (principal == null || !principal.isEnabled() || principal.isBanned()) {
            return rejected("OAUTH_SESSION_REQUIRED", null);
        }
        Long userId = principal.getId();
        Integer consumed = transactionTemplate.execute(status -> connectTokenRepository.consume(state, userId, Instant.now()));
        if (consumed == null || consumed != 1) {
            return rejected("OAUTH_STATE_INVALID", null);
        }
        boolean activePatron;
        try {
            final PatreonClient.PatreonTokens tokens = patreonClient.exchangeCode(code);
            final PatreonClient.PatreonIdentity identity = patreonClient.fetchIdentity(tokens.accessToken());
            activePatron = identity.activePatron();

            transactionTemplate.executeWithoutResult(status -> {
                User user = userRepository.findByIdForUpdate(userId)
                        .orElseThrow(() -> new InvalidTokenException("Invalid account"));
                if (!user.isEnabled() || user.isBanned()) {
                    throw new InvalidTokenException("Invalid account");
                }
                user.setPatreonUserId(identity.patreonUserId());
                user.setEncryptedPatreonAccessToken(cipher.convertToDatabaseColumn(tokens.accessToken()));
                user.setEncryptedPatreonRefreshToken(cipher.convertToDatabaseColumn(tokens.refreshToken()));
                userRepository.save(user);

                if (identity.activePatron()) {
                    subscriptionService.activateSupporterSubscription(user, subscriptionService.findOrCreateSupporterPlan());
                } else {
                    subscriptionService.deactivateSupporterSubscription(user);
                }
            });
        } catch (InvalidTokenException | DataIntegrityViolationException ex) {
            return rejected("OAUTH_FAILED", ex);
        }

        if (activePatron) {
            return redirectTo("connected");
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("https://www.patreon.com/cw/Tramodev/membership")).build();
    }

    private ResponseEntity<Void> rejected(String code, Throwable failure) {
        String trackingId = SafeLog.failure(log, "patreon_callback_rejected", code, failure);
        return ResponseEntity.status(HttpStatus.FOUND).header("X-Tracking-Id", trackingId)
                .location(URI.create(frontendUrl + "/settings?tab=plan&patreon=error")).build();
    }

    private ResponseEntity<Void> redirectTo(String patreonStatus) {
        URI location = URI.create(frontendUrl + "/settings?tab=plan&patreon=" + patreonStatus);
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }
}
