package com.tramo.backend.auth;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordChangeTest extends AbstractIntegrationTest {

    private static final String CURRENT = "Passw0rd123!";
    private static final String NEXT = "Str0ngerPass!99";

    private org.springframework.test.web.servlet.ResultActions changePassword(User user, String current, String next) throws Exception {
        return mockMvc.perform(put("/user/password")
                .header("Authorization", bearer(user))
                .contentType(APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}"));
    }

    private String login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void changePasswordRequiresAuthentication() throws Exception {
        mockMvc.perform(put("/user/password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + CURRENT + "\",\"newPassword\":\"" + NEXT + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingPasswordLetsTheUserLogInWithTheNewOne() throws Exception {
        User user = createUser("pwchange1");

        changePassword(user, CURRENT, NEXT).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/login")
                        .with(uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"pwchange1\",\"password\":\"" + NEXT + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void oldPasswordStopsWorkingAfterChange() throws Exception {
        User user = createUser("pwchange2");

        changePassword(user, CURRENT, NEXT).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/login")
                        .with(uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"pwchange2\",\"password\":\"" + CURRENT + "\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void wrongCurrentPasswordIsRejected() throws Exception {
        User user = createUser("pwchange3");

        changePassword(user, "NotMyPassw0rd!", NEXT).andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/login")
                        .with(uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"pwchange3\",\"password\":\"" + CURRENT + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void weakNewPasswordIsRejected() throws Exception {
        User user = createUser("pwchange4");

        changePassword(user, CURRENT, "short1!").andExpect(status().isBadRequest());
    }

    @Test
    void newPasswordWithoutSymbolIsRejected() throws Exception {
        User user = createUser("pwchange5");

        changePassword(user, CURRENT, "NoSymbolsHere123").andExpect(status().isBadRequest());
    }

    @Test
    void blankCurrentPasswordIsRejected() throws Exception {
        User user = createUser("pwchange6");

        changePassword(user, "", NEXT).andExpect(status().isBadRequest());
    }

    @Test
    void changingPasswordRevokesExistingRefreshTokens() throws Exception {
        User user = createUser("pwchange7");
        String body = login("pwchange7", CURRENT);
        String refreshToken = com.jayway.jsonpath.JsonPath.read(body, "$.refreshToken");

        changePassword(user, CURRENT, NEXT).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .with(uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void googleOnlyUserWithoutPasswordCannotChangeIt() throws Exception {
        User user = createUser("pwchange8");
        user.setPassword(null);
        userRepository.save(user);

        assertThat(userRepository.findById(user.getId()).orElseThrow().getPassword()).isNull();

        changePassword(user, CURRENT, NEXT).andExpect(status().isBadRequest());
    }
}
