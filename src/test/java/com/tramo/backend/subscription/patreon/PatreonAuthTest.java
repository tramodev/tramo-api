// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.subscription.patreon;

import com.jayway.jsonpath.JsonPath;
import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PatreonAuthTest extends AbstractIntegrationTest {

    private String startConnectFlow(User user) throws Exception {
        String body = mockMvc.perform(get("/api/auth/patreon/connect").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl").exists())
                .andReturn().getResponse().getContentAsString();
        String authorizeUrl = JsonPath.read(body, "$.authorizeUrl");
        assertThat(authorizeUrl).startsWith("https://www.patreon.com/oauth2/authorize");

        Matcher matcher = Pattern.compile("state=([^&]+)").matcher(authorizeUrl);
        assertThat(matcher.find()).isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    @Test
    void connectRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/auth/patreon/connect")).andExpect(status().isUnauthorized());
    }

    @Test
    void callbackActivatesSupporterSubscriptionForActivePatron() throws Exception {
        User user = createUser("patron");
        String state = startConnectFlow(user);

        when(patreonClient.exchangeCode("auth-code"))
                .thenReturn(new PatreonClient.PatreonTokens("access-tok", "refresh-tok"));
        when(patreonClient.fetchIdentity("access-tok"))
                .thenReturn(new PatreonClient.PatreonIdentity("patreon-123", true));

        mockMvc.perform(get("/api/auth/patreon/complete").header("Authorization", bearer(user)).param("code", "auth-code").param("state", state))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("patreon=connected")));

        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supporter").value(true));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getPatreonUserId())
                .isEqualTo("patreon-123");
    }

    @Test
    void callbackLinksAccountButLeavesFreeWhenNotAnActivePatron() throws Exception {
        User user = createUser("lapsed");
        String state = startConnectFlow(user);

        when(patreonClient.exchangeCode("auth-code"))
                .thenReturn(new PatreonClient.PatreonTokens("access-tok", "refresh-tok"));
        when(patreonClient.fetchIdentity("access-tok"))
                .thenReturn(new PatreonClient.PatreonIdentity("patreon-456", false));

        mockMvc.perform(get("/api/auth/patreon/complete").header("Authorization", bearer(user)).param("code", "auth-code").param("state", state))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "https://www.patreon.com/cw/Tramodev/membership"));

        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supporter").value(false));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getPatreonUserId())
                .isEqualTo("patreon-456");
    }

    @Test
    void publicCallbackOnlyRelaysToTheBrowser() throws Exception {
        User attacker = createUser("attacker");
        String state = startConnectFlow(attacker);
        mockMvc.perform(get("/api/auth/patreon/callback").param("code", "victim-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/api/auth/patreon/callback?")));
        assertThat(userRepository.findById(attacker.getId()).orElseThrow().getPatreonUserId()).isNull();
        org.mockito.Mockito.verify(patreonClient, org.mockito.Mockito.never()).exchangeCode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void anotherSessionCannotConsumeTheState() throws Exception {
        User attacker = createUser("stateattacker");
        User victim = createUser("statevictim");
        String state = startConnectFlow(attacker);
        mockMvc.perform(get("/api/auth/patreon/complete").header("Authorization", bearer(victim))
                        .param("code", "victim-code").param("state", state))
                .andExpect(status().isFound()).andExpect(header().string("Location", containsString("patreon=error")));
        assertThat(userRepository.findById(attacker.getId()).orElseThrow().getPatreonUserId()).isNull();
        org.mockito.Mockito.verify(patreonClient, org.mockito.Mockito.never()).exchangeCode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void alreadyLinkedPatreonReturnsAnError() throws Exception {
        User owner = createUser("linkedowner");
        owner.setPatreonUserId("patreon-taken");
        userRepository.save(owner);
        User other = createUser("linkedother");
        String state = startConnectFlow(other);
        when(patreonClient.exchangeCode("code")).thenReturn(new PatreonClient.PatreonTokens("access", "refresh"));
        when(patreonClient.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity("patreon-taken", true));
        mockMvc.perform(get("/api/auth/patreon/complete").header("Authorization", bearer(other))
                        .param("code", "code").param("state", state))
                .andExpect(status().isFound()).andExpect(header().string("Location", containsString("patreon=error")));
        assertThat(userRepository.findById(owner.getId()).orElseThrow().getPatreonUserId()).isEqualTo("patreon-taken");
        assertThat(userRepository.findById(other.getId()).orElseThrow().getPatreonUserId()).isNull();
    }

    @Test
    void stateIsSingleUse() throws Exception {
        User user = createUser("singleuse");
        String state = startConnectFlow(user);
        when(patreonClient.exchangeCode("code")).thenReturn(new PatreonClient.PatreonTokens("access", "refresh"));
        when(patreonClient.fetchIdentity("access")).thenReturn(new PatreonClient.PatreonIdentity("patreon-single", true));
        mockMvc.perform(get("/api/auth/patreon/complete").header("Authorization", bearer(user))
                        .param("code", "code").param("state", state))
                .andExpect(status().isFound()).andExpect(header().string("Location", containsString("patreon=connected")));
        mockMvc.perform(get("/api/auth/patreon/complete").header("Authorization", bearer(user))
                        .param("code", "code").param("state", state))
                .andExpect(status().isFound()).andExpect(header().string("Location", containsString("patreon=error")));
        org.mockito.Mockito.verify(patreonClient, org.mockito.Mockito.times(1)).exchangeCode("code");
    }

    @Test
    void callbackRejectsUnknownState() throws Exception {
        User user = createUser("unknownstate");
        mockMvc.perform(get("/api/auth/patreon/complete").header("Authorization", bearer(user)).param("code", "auth-code").param("state", "not-a-real-token"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("patreon=error")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "ban", "delete"})
    void callbackPreservesSecurityChangesMadeWhilePatreonResponds(String change) throws Exception {
        User user = createUser("concurrentpatron");
        String accessToken = bearer(user);
        String state = startConnectFlow(user);
        String newPassword = passwordEncoder.encode("Str0ngerPass!99");
        UUID sessionVersion = UUID.randomUUID();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(patreonClient.exchangeCode("auth-code"))
                .thenReturn(new PatreonClient.PatreonTokens("access-tok", "refresh-tok"));
        when(patreonClient.fetchIdentity("access-tok")).thenAnswer(call -> {
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Patreon response timed out");
            return new PatreonClient.PatreonIdentity("patreon-123", true);
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var callback = executor.submit(() -> mockMvc.perform(get("/api/auth/patreon/complete").header("Authorization", bearer(user))
                    .param("code", "auth-code").param("state", state)));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            if ("delete".equals(change)) {
                userRepository.deleteById(user.getId());
            } else {
                User current = userRepository.findById(user.getId()).orElseThrow();
                current.setPassword(newPassword);
                current.setSessionVersion(sessionVersion);
                current.setBanned("ban".equals(change));
                userRepository.saveAndFlush(current);
            }
            release.countDown();
            callback.get(10, TimeUnit.SECONDS)
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", containsString(
                            "password".equals(change) ? "patreon=connected" : "patreon=error")));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        if ("delete".equals(change)) {
            assertThat(userRepository.findById(user.getId())).isEmpty();
        } else {
            User current = userRepository.findById(user.getId()).orElseThrow();
            assertThat(current.getPassword()).isEqualTo(newPassword);
            assertThat(current.getSessionVersion()).isEqualTo(sessionVersion);
            assertThat(current.isBanned()).isEqualTo("ban".equals(change));
            assertThat(current.getPatreonUserId()).isEqualTo("password".equals(change) ? "patreon-123" : null);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscription WHERE user_id = ?", Long.class, user.getId()))
                .isEqualTo("password".equals(change) ? 1L : 0L);
        mockMvc.perform(get("/api/profile/me").header("Authorization", accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void callbackRejectsMissingParams() throws Exception {
        mockMvc.perform(get("/api/auth/patreon/complete"))
                .andExpect(status().isUnauthorized());
    }
}
