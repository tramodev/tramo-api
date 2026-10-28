// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.subscription.patreon;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.subscription.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.mockito.stubbing.Answer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;



class PatreonWebhookTest extends AbstractIntegrationTest {

    @Autowired
    PatreonWebhookSignatureRepository patreonWebhookSignatureRepository;

    @Autowired
    PatreonWebhookSignatureService patreonWebhookSignatureService;

    @MockitoSpyBean
    SubscriptionService subscriptionService;

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacMD5");
        mac.init(new SecretKeySpec(TEST_PATREON_WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacMD5"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String pledgePayload(String patreonUserId) {
        return pledgePayload(patreonUserId, "member-1");
    }

    private String pledgePayload(String patreonUserId, String memberId) {
        return """
                {"data":{"id":"%s","type":"member","attributes":{"patron_status":"active_patron"},
                "relationships":{"user":{"data":{"id":"%s","type":"user"}},
                "campaign":{"data":{"id":"tramo-campaign","type":"campaign"}}}}}"""
                .formatted(memberId, patreonUserId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedSubscriptionUpdateRollsBackSignatureAndCanBeRetried(boolean cancellation) throws Exception {
        User user = createUser("retryactivation");
        user.setPatreonUserId("patreon-retry");
        user.setEncryptedPatreonAccessToken(new com.tramo.backend.security.crypto.EncryptedStringConverter(TEST_TOKEN_ENCRYPTION_KEY).convertToDatabaseColumn("access"));
        userRepository.save(user);
        when(patreonClient.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity(user.getPatreonUserId(), true));
        String body = pledgePayload("patreon-retry");
        when(patreonClient.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity(user.getPatreonUserId(), !cancellation));
        String event = cancellation ? "members:pledge:delete" : "members:pledge:create";
        Answer<Object> failAfterUpdate = invocation -> {
            invocation.callRealMethod();
            throw new DataIntegrityViolationException("Simulated subscription failure");
        };
        if (cancellation) {
            subscriptionService.activateSupporterSubscription(user, subscriptionService.findOrCreateSupporterPlan());
            doAnswer(failAfterUpdate).doCallRealMethod().when(subscriptionService).deactivateSupporterSubscription(any());
        } else {
            doAnswer(failAfterUpdate).doCallRealMethod().when(subscriptionService).activateSupporterSubscription(any(), any());
        }

        mockMvc.perform(post("/api/webhooks/patreon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Patreon-Event", event)
                        .header("X-Patreon-Signature", sign(body))
                        .content(body))
                .andExpect(status().isInternalServerError());

        assertThat(patreonWebhookSignatureRepository.existsBySignature(sign(body))).isFalse();
        assertThat(subscriptionService.isSupporter(user)).isEqualTo(cancellation);

        mockMvc.perform(post("/api/webhooks/patreon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Patreon-Event", event)
                        .header("X-Patreon-Signature", sign(body))
                        .content(body))
                .andExpect(status().isOk());

        assertThat(patreonWebhookSignatureRepository.existsBySignature(sign(body))).isTrue();
        assertThat(subscriptionService.isSupporter(user)).isEqualTo(!cancellation);
    }

    @Test
    void activatesSupporterSubscriptionForLinkedUserOnPledgeCreate() throws Exception {
        User user = createUser("webhookpatron");
        user.setPatreonUserId("patreon-789");
        user.setEncryptedPatreonAccessToken(new com.tramo.backend.security.crypto.EncryptedStringConverter(TEST_TOKEN_ENCRYPTION_KEY).convertToDatabaseColumn("access"));
        userRepository.save(user);
        when(patreonClient.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity(user.getPatreonUserId(), true));

        String body = pledgePayload("patreon-789");
        mockMvc.perform(post("/api/webhooks/patreon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Patreon-Event", "members:pledge:create")
                        .header("X-Patreon-Signature", sign(body))
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supporter").value(true));
    }

    @Test
    void deactivatesSupporterSubscriptionOnPledgeDelete() throws Exception {
        User user = createUser("lapsingpatron");
        user.setPatreonUserId("patreon-999");
        user.setEncryptedPatreonAccessToken(new com.tramo.backend.security.crypto.EncryptedStringConverter(TEST_TOKEN_ENCRYPTION_KEY).convertToDatabaseColumn("access"));
        userRepository.save(user);
        when(patreonClient.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity(user.getPatreonUserId(), true));
        String createBody = pledgePayload("patreon-999");
        String deleteBody = pledgePayload("patreon-999", "member-1-cancelled");

        mockMvc.perform(post("/api/webhooks/patreon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Patreon-Event", "members:pledge:create")
                        .header("X-Patreon-Signature", sign(createBody))
                        .content(createBody))
                .andExpect(status().isOk());

        when(patreonClient.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity(user.getPatreonUserId(), false));
        mockMvc.perform(post("/api/webhooks/patreon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Patreon-Event", "members:pledge:delete")
                        .header("X-Patreon-Signature", sign(deleteBody))
                        .content(deleteBody))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supporter").value(false));
    }

    @Test
    void rejectsReplayOfCapturedPledgeCreateAfterCancellation() throws Exception {
        User user = createUser("replayvictim");
        user.setPatreonUserId("patreon-555");
        user.setEncryptedPatreonAccessToken(new com.tramo.backend.security.crypto.EncryptedStringConverter(TEST_TOKEN_ENCRYPTION_KEY).convertToDatabaseColumn("access"));
        userRepository.save(user);
        when(patreonClient.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity(user.getPatreonUserId(), true));

        String createBody = pledgePayload("patreon-555");
        String createSignature = sign(createBody);

        mockMvc.perform(post("/api/webhooks/patreon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Patreon-Event", "members:pledge:create")
                        .header("X-Patreon-Signature", createSignature)
                        .content(createBody))
                .andExpect(status().isOk());

        String deleteBody = pledgePayload("patreon-555", "member-1-cancelled");
        when(patreonClient.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity(user.getPatreonUserId(), false));
        mockMvc.perform(post("/api/webhooks/patreon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Patreon-Event", "members:pledge:delete")
                        .header("X-Patreon-Signature", sign(deleteBody))
                        .content(deleteBody))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/webhooks/patreon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Patreon-Event", "members:pledge:create")
                        .header("X-Patreon-Signature", createSignature)
                        .content(createBody))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supporter").value(false));
    }

    @Test
    void lateCreateAfterDeleteUsesCurrentPatreonStatus() throws Exception {
        User user = createUser("latecreate");
        user.setPatreonUserId("patreon-late");
        user.setEncryptedPatreonAccessToken(new com.tramo.backend.security.crypto.EncryptedStringConverter(TEST_TOKEN_ENCRYPTION_KEY).convertToDatabaseColumn("access"));
        userRepository.save(user);
        when(patreonClient.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity("patreon-late", false));
        for (String event : new String[]{"members:pledge:delete", "members:pledge:create"}) {
            String body = pledgePayload("patreon-late", event);
            mockMvc.perform(post("/api/webhooks/patreon").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Patreon-Event", event).header("X-Patreon-Signature", sign(body)).content(body))
                    .andExpect(status().isOk());
        }
        assertThat(subscriptionService.isSupporter(user)).isFalse();
    }

    @Test
    void missingSignatureIsForbidden() throws Exception {
        mockMvc.perform(post("/api/webhooks/patreon").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Patreon-Event", "members:pledge:create").content(pledgePayload("missing-signature")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsBadSignature() throws Exception {
        String body = pledgePayload("patreon-000");
        mockMvc.perform(post("/api/webhooks/patreon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Patreon-Event", "members:pledge:create")
                        .header("X-Patreon-Signature", "deadbeef")
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void ignoresPledgeForAnotherCampaign() throws Exception {
        User user = createUser("othercampaign");
        user.setPatreonUserId("patreon-other");
        user.setEncryptedPatreonAccessToken(new com.tramo.backend.security.crypto.EncryptedStringConverter(TEST_TOKEN_ENCRYPTION_KEY).convertToDatabaseColumn("access"));
        userRepository.save(user);
        when(patreonClient.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity(user.getPatreonUserId(), true));
        String body = pledgePayload("patreon-other").replace("tramo-campaign", "other-campaign");

        mockMvc.perform(post("/api/webhooks/patreon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Patreon-Event", "members:pledge:create")
                        .header("X-Patreon-Signature", sign(body))
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.supporter").value(false));
    }

    @Test
    void declinedPledgeUpdateRemovesSupporter() throws Exception {
        User user = createUser("declinedpatron");
        user.setPatreonUserId("patreon-declined");
        user.setEncryptedPatreonAccessToken(new com.tramo.backend.security.crypto.EncryptedStringConverter(TEST_TOKEN_ENCRYPTION_KEY).convertToDatabaseColumn("access"));
        userRepository.save(user);
        when(patreonClient.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity(user.getPatreonUserId(), true));
        String activeBody = pledgePayload("patreon-declined");
        String declinedBody = activeBody.replace("active_patron", "declined_patron");

        for (String body : new String[]{activeBody, declinedBody}) {
            when(patreonClient.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity(user.getPatreonUserId(), body.equals(activeBody)));
            mockMvc.perform(post("/api/webhooks/patreon")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Patreon-Event", "members:pledge:update")
                            .header("X-Patreon-Signature", sign(body))
                            .content(body))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.supporter").value(false));
    }

    @Test
    void ignoresPledgeForUnlinkedPatreonUser() throws Exception {
        String body = pledgePayload("patreon-unknown");
        mockMvc.perform(post("/api/webhooks/patreon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Patreon-Event", "members:pledge:create")
                        .header("X-Patreon-Signature", sign(body))
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void purgeDeletesOnlySignaturesPastRetention() {
        PatreonWebhookSignature old = new PatreonWebhookSignature("sig-old");
        old.setProcessedAt(Instant.now().minus(60, ChronoUnit.DAYS));
        patreonWebhookSignatureRepository.save(old);

        PatreonWebhookSignature recent = new PatreonWebhookSignature("sig-recent");
        recent.setProcessedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        patreonWebhookSignatureRepository.save(recent);

        patreonWebhookSignatureService.purgeProcessedSignatures();

        assertThat(patreonWebhookSignatureRepository.findAll())
                .extracting(PatreonWebhookSignature::getSignature)
                .contains("sig-recent")
                .doesNotContain("sig-old");
    }
}
