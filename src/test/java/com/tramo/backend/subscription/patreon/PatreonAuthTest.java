// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.subscription.patreon;

import com.jayway.jsonpath.JsonPath;
import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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

        mockMvc.perform(get("/api/auth/patreon/callback").param("code", "auth-code").param("state", state))
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

        mockMvc.perform(get("/api/auth/patreon/callback").param("code", "auth-code").param("state", state))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("patreon=connected")));

        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supporter").value(false));
    }

    @Test
    void callbackRejectsUnknownState() throws Exception {
        mockMvc.perform(get("/api/auth/patreon/callback").param("code", "auth-code").param("state", "not-a-real-token"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("patreon=error")));
    }

    @Test
    void callbackRejectsMissingParams() throws Exception {
        mockMvc.perform(get("/api/auth/patreon/callback"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("patreon=error")));
    }
}
