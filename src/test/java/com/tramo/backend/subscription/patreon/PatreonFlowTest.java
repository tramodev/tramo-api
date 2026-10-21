package com.tramo.backend.subscription.patreon;

import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatreonFlowTest {
    private final com.tramo.backend.security.crypto.EncryptedStringConverter cipher = new com.tramo.backend.security.crypto.EncryptedStringConverter(java.util.Base64.getEncoder().encodeToString(new byte[32]));
    private final User user = new User();
    private final UserRepository users = mock(UserRepository.class);
    private final SubscriptionService subscriptions = mock(SubscriptionService.class);

    @Test
    void connectedNonSupporterGoesStraightToTramoMembership() {
        PatreonController controller = callbackController(false);

        var response = controller.callback("code", "state", null, null);

        assertThat(response.getHeaders().getLocation().toString())
                .isEqualTo("https://www.patreon.com/cw/Tramodev/membership");
        assertThat(user.getPatreonUserId()).isEqualTo("patreon-user");
        assertThat(user.getEncryptedPatreonAccessToken()).isNotEqualTo("access");
        assertThat(cipher.convertToEntityAttribute(user.getEncryptedPatreonAccessToken())).isEqualTo("access");
        assertThat(cipher.convertToEntityAttribute(user.getEncryptedPatreonRefreshToken())).isEqualTo("refresh");
        verify(users).save(user);
        verify(subscriptions, never()).activateSupporterSubscription(any(), any());
    }

    @Test
    void existingSupporterReturnsToTramoWithBenefitsActivated() {
        PatreonController controller = callbackController(true);

        var response = controller.callback("code", "state", null, null);

        assertThat(response.getHeaders().getLocation().toString())
                .isEqualTo("https://tramo.test/settings?tab=plan&patreon=connected");
        verify(subscriptions).activateSupporterSubscription(eq(user), any());
    }

    @Test
    void authorizationDenialReturnsAnErrorWithoutGrantingBenefits() {
        var response = callbackController(false).callback(null, "state", "access_denied", null);

        assertThat(response.getHeaders().getLocation().toString()).contains("patreon=error");
        verify(subscriptions, never()).activateSupporterSubscription(any(), any());
    }

    @Test
    void signedActiveTramoMembershipActivatesSupporter() throws Exception {
        String body = payload("tramo-campaign", "active_patron");

        var response = webhookController().handle(body, sign(body), "members:pledge:create");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(subscriptions).activateSupporterSubscription(eq(user), any());
    }

    @Test
    void signedMembershipForAnotherCampaignDoesNotGrantBenefits() throws Exception {
        String body = payload("other-campaign", "active_patron");

        webhookController().handle(body, sign(body), "members:pledge:create");

        verify(subscriptions, never()).activateSupporterSubscription(any(), any());
        verify(users, never()).findByPatreonUserId(any());
    }

    @Test
    void signedMembershipWithoutStatusDoesNotGrantBenefits() throws Exception {
        String body = payload("tramo-campaign", "");

        webhookController().handle(body, sign(body), "members:pledge:update");

        verify(subscriptions, never()).activateSupporterSubscription(any(), any());
        verify(subscriptions, never()).deactivateSupporterSubscription(any());
    }

    @Test
    void declinedMembershipRemovesBenefits() throws Exception {
        String body = payload("tramo-campaign", "declined_patron");

        webhookController().handle(body, sign(body), "members:pledge:update");

        verify(subscriptions).deactivateSupporterSubscription(user);
        verify(subscriptions, never()).activateSupporterSubscription(any(), any());
    }

    @Test
    void invalidWebhookSignatureIsRejected() {
        var response = webhookController().handle(payload("tramo-campaign", "active_patron"), "invalid", "members:pledge:create");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(subscriptions, never()).activateSupporterSubscription(any(), any());
    }

    @Test
    void webhookWithoutEventTypeDoesNotChangeBenefits() throws Exception {
        String body = payload("tramo-campaign", "active_patron");

        var response = webhookController().handle(body, sign(body), null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(subscriptions, never()).activateSupporterSubscription(any(), any());
        verify(subscriptions, never()).deactivateSupporterSubscription(any());
    }

    @Test
    void syncRecognizesPaymentWithoutWaitingForWebhook() {
        PatreonClient client = linkedClient(true);

        new PatreonMembershipService(client, subscriptions, cipher).sync(user);

        verify(subscriptions).activateSupporterSubscription(eq(user), any());
        verify(subscriptions).getStatus(user);
    }

    @Test
    void syncRemovesBenefitsWhenMembershipIsNotActive() {
        PatreonClient client = linkedClient(false);

        new PatreonMembershipService(client, subscriptions, cipher).sync(user);

        verify(subscriptions).deactivateSupporterSubscription(user);
        verify(subscriptions, never()).activateSupporterSubscription(any(), any());
    }

    @Test
    void syncDoesNotContactPatreonForUnlinkedAccount() {
        PatreonClient client = mock(PatreonClient.class);

        new PatreonMembershipService(client, subscriptions, cipher).sync(user);

        verify(client, never()).fetchIdentity(any());
        verify(subscriptions).getStatus(user);
    }

    @Test
    void syncRejectsMismatchedIdentityWithoutChangingBenefits() {
        PatreonClient client = linkedClient(true);
        when(client.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity("another-user", true));

        assertThatThrownBy(() -> new PatreonMembershipService(client, subscriptions, cipher).sync(user))
                .isInstanceOf(InvalidTokenException.class);

        verify(subscriptions, never()).activateSupporterSubscription(any(), any());
        verify(subscriptions, never()).deactivateSupporterSubscription(any());
    }

    @Test
    void syncPreservesBenefitsWhenPatreonIsUnavailable() {
        PatreonClient client = linkedClient(false);
        when(client.fetchIdentity("access")).thenThrow(new InvalidTokenException("Patreon unavailable"));

        assertThatThrownBy(() -> new PatreonMembershipService(client, subscriptions, cipher).sync(user))
                .isInstanceOf(InvalidTokenException.class);

        verify(subscriptions, never()).activateSupporterSubscription(any(), any());
        verify(subscriptions, never()).deactivateSupporterSubscription(any());
    }

    private PatreonClient linkedClient(boolean active) {
        user.setPatreonUserId("patreon-user");
        user.setEncryptedPatreonAccessToken(cipher.convertToDatabaseColumn("access"));
        PatreonClient client = mock(PatreonClient.class);
        when(client.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity("patreon-user", active));
        return client;
    }

    private PatreonController callbackController(boolean active) {
        user.setId(1L);
        user.setEmailVerified(true);
        when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        var tokens = mock(PatreonConnectTokenRepository.class);
        var client = mock(PatreonClient.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        PatreonConnectToken state = new PatreonConnectToken();
        state.setUser(user);
        state.setExpiresAt(Instant.now().plusSeconds(60));
        when(tokens.findByToken("state")).thenReturn(Optional.of(state));
        when(client.exchangeCode("code")).thenReturn(new PatreonClient.PatreonTokens("access", "refresh"));
        when(client.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity("patreon-user", active));
        return new PatreonController(transactions, tokens, client, subscriptions, users,
                "client-id", "https://api.tramo.test/callback", "https://tramo.test", cipher);
    }

    private PatreonWebhookController webhookController() {
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var controller = new PatreonWebhookController("secret", users, subscriptions, new ObjectMapper(),
                mock(PatreonWebhookSignatureRepository.class), transactions, new org.springframework.mock.env.MockEnvironment());
        ReflectionTestUtils.setField(controller, "campaignId", "tramo-campaign");
        when(users.findByPatreonUserId("patreon-user")).thenReturn(Optional.of(user));
        return controller;
    }

    private String payload(String campaign, String status) {
        return """
                {"data":{"type":"member","attributes":{"patron_status":"%s"},"relationships":{
                "user":{"data":{"id":"patreon-user"}},"campaign":{"data":{"id":"%s"}}}}}"""
                .formatted(status, campaign);
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacMD5");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacMD5"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
