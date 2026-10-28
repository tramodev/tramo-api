// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.subscription.patreon;

import com.tramo.backend.common.SafeLog;
import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.exception.RequestValidationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;
    private final PatreonWebhookSignatureRepository webhookSignatureRepository;
    private final TransactionTemplate transactionTemplate;
    private final PatreonMembershipService memberships;

    public PatreonWebhookController(@Value("${app.patreon.webhook-secret}") String webhookSecret,
                                     UserRepository userRepository,
                                     ObjectMapper objectMapper,
                                      PatreonWebhookSignatureRepository webhookSignatureRepository,
                                      PlatformTransactionManager transactionManager, Environment environment, PatreonMembershipService memberships) {
        if (webhookSecret.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) throw new IllegalStateException("PATREON_WEBHOOK_SECRET is required in production");
            log.warn("event=patreon_webhook_disabled code=MISSING_SECRET");
        }
        this.memberships = memberships;
        this.webhookSecret = webhookSecret;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.webhookSignatureRepository = webhookSignatureRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PostMapping
    public ResponseEntity<Void> handle(@RequestBody String rawBody,
                                        @RequestHeader(value = "X-Patreon-Signature", required = false) String signature,
                                        @RequestHeader(value = "X-Patreon-Event", required = false) String eventType) {
        if (!signatureValid(rawBody, signature)) {
            log.warn("event=patreon_webhook_rejected code=BAD_SIGNATURE");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (eventType == null || (!ACTIVATION_EVENTS.contains(eventType) && !DEACTIVATION_EVENTS.contains(eventType))) {
            log.warn("event=patreon_webhook_ignored code=UNKNOWN_EVENT");
            return ResponseEntity.ok().build();
        }

        if (webhookSignatureRepository.existsBySignature(signature)) return ResponseEntity.ok().build();
        JsonNode member = extractMember(rawBody);
        if (member == null) return ResponseEntity.ok().build();
        String patreonUserId = member.path("relationships").path("user").path("data").path("id").asText();
        User user = userRepository.findByPatreonUserId(patreonUserId).orElse(null);
        if (user == null) return ResponseEntity.ok().build();
        PatreonClient.PatreonIdentity identity = null;
        try {
            identity = memberships.fetchIdentity(user);
        } catch (InvalidTokenException | RequestValidationException failure) {
            SafeLog.failure(log, "patreon_webhook_deferred", "MEMBERSHIP_UNAVAILABLE", failure);
            String patronStatus = member.path("attributes").path("patron_status").asText("");
            if (!DEACTIVATION_EVENTS.contains(eventType)
                    && !Set.of("former_patron", "declined_patron").contains(patronStatus)) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
        }
        PatreonClient.PatreonIdentity verified = identity == null
                ? new PatreonClient.PatreonIdentity(patreonUserId, false) : identity;
        try {
            return transactionTemplate.execute(status -> process(user, verified, signature));
        } catch (DataIntegrityViolationException ex) {
            if (!webhookSignatureRepository.existsBySignature(signature)) {
                throw ex;
            }
            log.info("Patreon webhook replay detected, ignoring");
            return ResponseEntity.ok().build();
        }
    }

    private ResponseEntity<Void> process(User observed, PatreonClient.PatreonIdentity identity, String signature) {
        webhookSignatureRepository.saveAndFlush(new PatreonWebhookSignature(signature));
        User current = userRepository.findByIdForUpdate(observed.getId()).orElse(null);
        if (current != null && java.util.Objects.equals(observed.getPatreonUserId(), current.getPatreonUserId())
                && java.util.Objects.equals(observed.getEncryptedPatreonAccessToken(), current.getEncryptedPatreonAccessToken())) {
            memberships.apply(current, identity);
        }
        return ResponseEntity.ok().build();
    }

    
    private boolean signatureValid(String rawBody, String signature) {
        if (signature == null || signature.isBlank() || webhookSecret.isBlank()) return false;
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
