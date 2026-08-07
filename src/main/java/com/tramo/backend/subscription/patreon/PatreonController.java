package com.tramo.backend.subscription.patreon;

import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    public PatreonController(PlatformTransactionManager transactionManager,
                              PatreonConnectTokenRepository connectTokenRepository,
                              PatreonClient patreonClient,
                              SubscriptionService subscriptionService,
                              UserRepository userRepository,
                              @Value("${app.patreon.client-id}") String clientId,
                              @Value("${app.patreon.redirect-uri}") String redirectUri,
                              @Value("${app.frontend-url}") String frontendUrl) {
        this.connectTokenRepository = connectTokenRepository;
        this.patreonClient = patreonClient;
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.frontendUrl = frontendUrl;
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
                                          @RequestParam(required = false) String error,
                                          @RequestParam(name = "error_description", required = false) String errorDescription) {
        if (error != null) {
            log.warn("Patreon callback returned error={} description={}", error, errorDescription);
            return redirectTo("error");
        }
        if (code == null || state == null) {
            log.warn("Patreon callback missing code/state (code={}, state={})", code != null, state != null);
            return redirectTo("error");
        }

        PatreonConnectToken connectToken = connectTokenRepository.findByToken(state).orElse(null);
        if (connectToken == null) {
            log.warn("Patreon callback state not found (already consumed or invalid): {}", state);
            return redirectTo("error");
        }
        connectTokenRepository.delete(connectToken);
        if (connectToken.getExpiresAt().isBefore(Instant.now())) {
            log.warn("Patreon connect token expired at {} for user {}", connectToken.getExpiresAt(), connectToken.getUser().getId());
            return redirectTo("error");
        }

        User user;
        try {
            final PatreonClient.PatreonTokens tokens = patreonClient.exchangeCode(code);
            final PatreonClient.PatreonIdentity identity = patreonClient.fetchIdentity(tokens.accessToken());

            user = connectToken.getUser();
            transactionTemplate.executeWithoutResult(status -> {
                user.setPatreonUserId(identity.patreonUserId());
                user.setPatreonAccessToken(tokens.accessToken());
                user.setPatreonRefreshToken(tokens.refreshToken());
                userRepository.save(user);

                if (identity.activePatron()) {
                    subscriptionService.activateSupporterSubscription(user, subscriptionService.findOrCreateSupporterPlan());
                }
            });
        } catch (InvalidTokenException ex) {
            log.warn("Patreon code exchange/identity fetch failed: {}", ex.getMessage(), ex.getCause());
            return redirectTo("error");
        }

        return redirectTo("connected");
    }

    private ResponseEntity<Void> redirectTo(String patreonStatus) {
        URI location = URI.create(frontendUrl + "/settings?tab=plan&patreon=" + patreonStatus);
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }
}
