// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.subscription.patreon;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;



@RestController
@RequestMapping("/api/webhooks/patreon")
public class PatreonWebhookController {
    private static final Logger log = LoggerFactory.getLogger(PatreonWebhookController.class);

    private static final Set<String> ACTIVATION_EVENTS = Set.of("members:pledge:create", "members:pledge:update");
    private static final Set<String> DEACTIVATION_EVENTS = Set.of("members:pledge:delete");

    private final String webhookSecret;
    @Value("${app.patreon.campaign-id}")
    private String campaignId;
    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;
    private final PatreonWebhookSignatureRepository webhookSignatureRepository;
    private final TransactionTemplate transactionTemplate;

    public PatreonWebhookController(@Value("${app.patreon.webhook-secret}") String webhookSecret,
                                     UserRepository userRepository,
                                     SubscriptionService subscriptionService,
                                     ObjectMapper objectMapper,
                                      PatreonWebhookSignatureRepository webhookSignatureRepository,
                                      PlatformTransactionManager transactionManager, Environment environment) {
        if (webhookSecret.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) throw new IllegalStateException("PATREON_WEBHOOK_SECRET is required in production");
            log.warn("event=patreon_webhook_disabled code=MISSING_SECRET");
        }
        this.webhookSecret = webhookSecret;
        this.userRepository = userRepository;
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
        this.webhookSignatureRepository = webhookSignatureRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PostMapping
    public ResponseEntity<Void> handle(@RequestBody String rawBody,
                                        @RequestHeader("X-Patreon-Signature") String signature,
                                        @RequestHeader(value = "X-Patreon-Event", required = false) String eventType) {
        if (!signatureValid(rawBody, signature)) {
            log.warn("event=patreon_webhook_rejected code=BAD_SIGNATURE");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (eventType == null || (!ACTIVATION_EVENTS.contains(eventType) && !DEACTIVATION_EVENTS.contains(eventType))) {
            log.warn("event=patreon_webhook_ignored code=UNKNOWN_EVENT");
            return ResponseEntity.ok().build();
        }

        try {
            return transactionTemplate.execute(status -> process(rawBody, signature, eventType));
        } catch (DataIntegrityViolationException ex) {
            if (!webhookSignatureRepository.existsBySignature(signature)) {
                throw ex;
            }
            log.info("Patreon webhook replay detected, ignoring");
            return ResponseEntity.ok().build();
        }
    }

    private ResponseEntity<Void> process(String rawBody, String signature, String eventType) {
        webhookSignatureRepository.saveAndFlush(new PatreonWebhookSignature(signature));
        JsonNode member = extractMember(rawBody);
        if (member == null) {
            log.warn("event=patreon_webhook_ignored code=INVALID_MEMBER");
            return ResponseEntity.ok().build();
        }
        String patreonUserId = member.path("relationships").path("user").path("data").path("id").asText();

        Optional<User> user = userRepository.findByPatreonUserId(patreonUserId);
        if (user.isEmpty()) {
            log.info("event=patreon_webhook_ignored code=UNLINKED_ACCOUNT");
            return ResponseEntity.ok().build();
        }

        if (DEACTIVATION_EVENTS.contains(eventType)) {
            subscriptionService.deactivateSupporterSubscription(user.get());
        } else if (ACTIVATION_EVENTS.contains(eventType)) {
            String patronStatus = member.path("attributes").path("patron_status").asText("");
            if ("active_patron".equals(patronStatus)) {
                subscriptionService.activateSupporterSubscription(user.get(), subscriptionService.findOrCreateSupporterPlan());
            } else if ("former_patron".equals(patronStatus) || "declined_patron".equals(patronStatus)) {
                subscriptionService.deactivateSupporterSubscription(user.get());
            }
        }
        return ResponseEntity.ok().build();
    }

    
    private boolean signatureValid(String rawBody, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacMD5"));
            byte[] digest = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String computed = HexFormat.of().formatHex(digest);
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return false;
        }
    }

    
    
    private JsonNode extractMember(String rawBody) {
        try {
            JsonNode member = objectMapper.readTree(rawBody).path("data");
            JsonNode userId = member.path("relationships").path("user").path("data").path("id");
            String memberCampaignId = member.path("relationships").path("campaign").path("data").path("id").asText("");
            if (campaignId.isBlank() || !"member".equals(member.path("type").asText()) || !campaignId.equals(memberCampaignId)
                    || !userId.isString() || userId.asText().isBlank()) {
                return null;
            }
            return member;
        } catch (Exception ex) {
            return null;
        }
    }
}
