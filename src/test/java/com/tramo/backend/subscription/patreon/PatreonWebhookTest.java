package com.tramo.backend.subscription.patreon;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;



class PatreonWebhookTest extends AbstractIntegrationTest {

    @Autowired
    PatreonWebhookSignatureRepository patreonWebhookSignatureRepository;

    @Autowired
    PatreonWebhookSignatureService patreonWebhookSignatureService;

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
                {"data":{"id":"%s","type":"member","relationships":{"user":{"data":{"id":"%s","type":"user"}}}}}"""
                .formatted(memberId, patreonUserId);
    }

    @Test
    void activatesSupporterSubscriptionForLinkedUserOnPledgeCreate() throws Exception {
        User user = createUser("webhookpatron");
        user.setPatreonUserId("patreon-789");
        userRepository.save(user);

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
        userRepository.save(user);
        String createBody = pledgePayload("patreon-999");
        String deleteBody = pledgePayload("patreon-999", "member-1-cancelled");

        mockMvc.perform(post("/api/webhooks/patreon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Patreon-Event", "members:pledge:create")
                        .header("X-Patreon-Signature", sign(createBody))
                        .content(createBody))
                .andExpect(status().isOk());

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
        userRepository.save(user);

        String createBody = pledgePayload("patreon-555");
        String createSignature = sign(createBody);

        mockMvc.perform(post("/api/webhooks/patreon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Patreon-Event", "members:pledge:create")
                        .header("X-Patreon-Signature", createSignature)
                        .content(createBody))
                .andExpect(status().isOk());

        String deleteBody = pledgePayload("patreon-555", "member-1-cancelled");
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
