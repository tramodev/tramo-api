package com.tramo.backend.security;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RateLimitTest extends AbstractIntegrationTest {

    private MvcResult attemptLogin(String ip, String username) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"Whatever1!"}""".formatted(username)))
                .andReturn();
    }

    @Test
    void loginAllowsTenAttemptsPerMinutePerIp() throws Exception {
        String ip = "172.16.0.1";
        String username = uniqueUsername();
        for (int i = 0; i < 10; i++) {
            assertThat(attemptLogin(ip, username).getResponse().getStatus())
                    .as("attempt %d should not be rate limited", i + 1)
                    .isEqualTo(401);
        }
        assertThat(attemptLogin(ip, username).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void rateLimitIsPerIpWhenIdentityDiffers() throws Exception {
        String ip = "172.16.0.2";
        String username = uniqueUsername();
        for (int i = 0; i < 11; i++) {
            attemptLogin(ip, username);
        }
        assertThat(attemptLogin(ip, username).getResponse().getStatus()).isEqualTo(429);
        assertThat(attemptLogin("172.16.0.3", uniqueUsername()).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void loginIdentityBucketPersistsAcrossRotatingIps() throws Exception {
        String username = uniqueUsername();
        for (int i = 0; i < 10; i++) {
            assertThat(attemptLogin("172.16.1." + i, username).getResponse().getStatus())
                    .as("attempt %d from a fresh IP should not be rate limited yet", i + 1)
                    .isEqualTo(401);
        }
        assertThat(attemptLogin("172.16.1.99", username).getResponse().getStatus())
                .as("shared identity bucket should be exhausted despite every attempt using a fresh IP")
                .isEqualTo(429);
    }

    @Test
    void forgotPasswordIsRateLimited() throws Exception {
        String ip = "172.16.0.4";
        String email = uniqueEmail();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/forgot-password")
                            .with(remoteAddr(ip))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"%s"}""".formatted(email)))
                    .andExpect(status().isNoContent());
        }
        mockMvc.perform(post("/api/auth/forgot-password")
                        .with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}""".formatted(email)))
                .andExpect(status().is(429));
    }

    @Test
    void forgotPasswordIdentityBucketPersistsAcrossRotatingIps() throws Exception {
        String email = uniqueEmail();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/forgot-password")
                            .with(remoteAddr("172.16.2." + i))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"%s"}""".formatted(email)))
                    .andExpect(status().isNoContent());
        }
        mockMvc.perform(post("/api/auth/forgot-password")
                        .with(remoteAddr("172.16.2.99"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}""".formatted(email)))
                .andExpect(status().is(429));
    }

    @Test
    void registerStaysIpOnlyEvenWithRotatingIdentities() throws Exception {
        String ip = "172.16.3.1";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/auth/register")
                            .with(remoteAddr(ip))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"%s","email":"%s","password":"Whatever1!","captchaToken":"t"}"""
                                    .formatted(uniqueUsername(), uniqueEmail())));
        }
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"Whatever1!","captchaToken":"t"}"""
                                .formatted(uniqueUsername(), uniqueEmail())))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("register has no identity to rotate around, only the IP bucket applies")
                .isEqualTo(429);
    }

    @Test
    void authenticatedDefaultTierIsPerUserNotPerIp() throws Exception {
        User owner = createUser("bookmarkowner");
        Project project = createProject(owner, "Bookmark target", "published");
        User userA = createUser("bookmarkusera");
        User userB = createUser("bookmarkuserb");
        String ip = "172.16.4.1";

        for (int i = 0; i < 60; i++) {
            mockMvc.perform(post("/api/project/" + pid(project) + "/bookmark")
                    .with(remoteAddr(ip))
                    .header("Authorization", bearer(userA)));
        }
        mockMvc.perform(post("/api/project/" + pid(project) + "/bookmark")
                        .with(remoteAddr(ip))
                        .header("Authorization", bearer(userA)))
                .andExpect(status().is(429));

        mockMvc.perform(post("/api/project/" + pid(project) + "/bookmark")
                        .with(remoteAddr(ip))
                        .header("Authorization", bearer(userB)))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedTightTierSharedAcrossUserIps() throws Exception {
        User owner = createUser("reportowner");
        Project project = createProject(owner, "Report target", "published");
        User reporter = createUser("reportuser");

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/project/" + pid(project) + "/report")
                            .with(remoteAddr("172.16.5." + i))
                            .header("Authorization", bearer(reporter))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"reason":"spam"}"""))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/project/" + pid(project) + "/report")
                        .with(remoteAddr("172.16.5.99"))
                        .header("Authorization", bearer(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"spam"}"""))
                .andExpect(status().is(429));
    }

    @Test
    void tightAndDefaultTiersAreIndependentPerUser() throws Exception {
        User owner = createUser("tierowner");
        Project project = createProject(owner, "Tier target", "published");
        User user = createUser("tieruser");

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/project/" + pid(project) + "/report")
                    .header("Authorization", bearer(user))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"reason":"spam"}"""));
        }
        mockMvc.perform(post("/api/project/" + pid(project) + "/report")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"spam"}"""))
                .andExpect(status().is(429));

        mockMvc.perform(post("/api/project/" + pid(project) + "/bookmark")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk());
    }

    @Test
    void adminRoutesAreExemptFromUserRateLimit() throws Exception {
        User admin = createAdmin("rateadmin");
        User target = createUser("ratetarget");

        for (int i = 0; i < 35; i++) {
            mockMvc.perform(post("/api/admin/users/" + target.getId() + "/unban")
                            .header("Authorization", bearer(admin)))
                    .andExpect(status().isOk());
        }
    }
}
