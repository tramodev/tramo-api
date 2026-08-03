package com.tramo.backend.subscription.patreon;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;

    public PatreonWebhookController(@Value("${app.patreon.webhook-secret}") String webhookSecret,
                                     UserRepository userRepository,
                                     SubscriptionService subscriptionService,
                                     ObjectMapper objectMapper) {
        this.webhookSecret = webhookSecret;
        this.userRepository = userRepository;
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Void> handle(@RequestBody String rawBody,
                                        @RequestHeader("X-Patreon-Signature") String signature,
                                        @RequestHeader(value = "X-Patreon-Event", required = false) String eventType) {
        if (!signatureValid(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String patreonUserId = extractPatreonUserId(rawBody);
        if (patreonUserId == null) {
            log.warn("Patreon webhook payload missing member->user relationship, ignoring");
            return ResponseEntity.ok().build();
        }

        Optional<User> user = userRepository.findByPatreonUserId(patreonUserId);
        if (user.isEmpty()) {
            log.info("Patreon webhook for unlinked patreonUserId={}", patreonUserId);
            return ResponseEntity.ok().build();
        }

        if (DEACTIVATION_EVENTS.contains(eventType)) {
            subscriptionService.deactivateSupporterSubscription(user.get());
        } else if (ACTIVATION_EVENTS.contains(eventType)) {
            subscriptionService.activateSupporterSubscription(user.get(), subscriptionService.findOrCreateSupporterPlan());
        } else {
            log.warn("Patreon webhook unrecognized eventType={}, ignoring", eventType);
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

    
    
    private String extractPatreonUserId(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode userId = root.path("data").path("relationships").path("user").path("data").path("id");
            return userId.isMissingNode() || userId.isNull() ? null : userId.asText();
        } catch (Exception ex) {
            return null;
        }
    }
}
