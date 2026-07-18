package com.tramo.backend.security;

import com.tramo.backend.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RateLimitTest extends AbstractIntegrationTest {

    private MvcResult attemptLogin(String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"nobody","password":"Whatever1!"}"""))
                .andReturn();
    }

    @Test
    void loginAllowsTenAttemptsPerMinutePerIp() throws Exception {
        String ip = "172.16.0.1";
        for (int i = 0; i < 10; i++) {
            assertThat(attemptLogin(ip).getResponse().getStatus())
                    .as("attempt %d should not be rate limited", i + 1)
                    .isEqualTo(401);
        }
        assertThat(attemptLogin(ip).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void rateLimitIsPerIp() throws Exception {
        String ip = "172.16.0.2";
        for (int i = 0; i < 11; i++) {
            attemptLogin(ip);
        }
        assertThat(attemptLogin(ip).getResponse().getStatus()).isEqualTo(429);
        assertThat(attemptLogin("172.16.0.3").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void otherAuthEndpointsAreNotRateLimited() throws Exception {
        for (int i = 0; i < 15; i++) {
            mockMvc.perform(post("/api/auth/forgot-password")
                            .with(remoteAddr("172.16.0.4"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"nobody@example.com"}"""))
                    .andExpect(status().isNoContent());
        }
    }
}
