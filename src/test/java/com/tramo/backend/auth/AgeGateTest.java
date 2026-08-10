package com.tramo.backend.auth;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.auth.service.GoogleTokenVerifier;
import com.tramo.backend.user.entity.User;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgeGateTest extends AbstractIntegrationTest {

    private String registerJson(String username, String email, String birthDate) {
        return """
                {"username":"%s","email":"%s","password":"Passw0rd123!","captchaToken":"test-token","birthDate":"%s"}"""
                .formatted(username, email, birthDate);
    }

    private org.springframework.test.web.servlet.ResultActions register(String body, org.springframework.test.web.servlet.request.RequestPostProcessor ip) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .with(ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void registerRejectsUnderageBirthDate() throws Exception {
        register(registerJson("toobrief", "toobrief@example.com", LocalDate.now().minusYears(12).toString()), uniqueIp())
                .andExpect(status().isForbidden());

        assertThat(userRepository.findByUsernameIgnoreCase("toobrief")).isEmpty();
    }

    @Test
    void registerAcceptsExactlyMinAgeBirthDateAndRejectsOneDayShort() throws Exception {
        register(registerJson("exactage", "exactage@example.com", LocalDate.now().minusYears(13).toString()), uniqueIp())
                .andExpect(status().isOk());
        assertThat(userRepository.findByUsernameIgnoreCase("exactage")).isPresent();

        register(registerJson("almostage", "almostage@example.com", LocalDate.now().minusYears(13).plusDays(1).toString()), uniqueIp())
                .andExpect(status().isForbidden());
        assertThat(userRepository.findByUsernameIgnoreCase("almostage")).isEmpty();
    }

    @Test
    void registerRejectsMissingBirthDate() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(uniqueIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"nobirthdate","email":"nobirthdate@example.com","password":"Passw0rd123!","captchaToken":"test-token"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.birthDate").exists());
    }

    @Test
    void registerRejectsFutureBirthDate() throws Exception {
        register(registerJson("future", "future@example.com", LocalDate.now().plusDays(1).toString()), uniqueIp())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.birthDate").exists());
    }

    @Test
    void repeatedRejectionFromSameIpStaysBlockedButOtherIpsAreUnaffected() throws Exception {
        org.springframework.test.web.servlet.request.RequestPostProcessor ip = uniqueIp();

        register(registerJson("firsttry", "firsttry@example.com", LocalDate.now().minusYears(5).toString()), ip)
                .andExpect(status().isForbidden());

        register(registerJson("secondtry", "secondtry@example.com", LocalDate.now().minusYears(30).toString()), ip)
                .andExpect(status().isForbidden());
        assertThat(userRepository.findByUsernameIgnoreCase("secondtry")).isEmpty();

        register(registerJson("differentip", "differentip@example.com", LocalDate.now().minusYears(30).toString()), uniqueIp())
                .andExpect(status().isOk());
    }

    private void stubGoogleToken(String email, String name) {
        when(googleTokenVerifier.verify(anyString()))
                .thenReturn(new GoogleTokenVerifier.GoogleTokenPayload(email, name));
    }

    @Test
    void googleSignInGatesContentCreationUntilBirthDateIsSet() throws Exception {
        stubGoogleToken("newgoogleuser@gmail.com", "New Google User");

        String response = mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idToken":"fake-token"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresBirthDate").value(true))
                .andReturn().getResponse().getContentAsString();

        String accessToken = JsonPath.read(response, "$.accessToken");

        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());

        String birthDateResponse = mockMvc.perform(post("/api/auth/birth-date")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"birthDate":"%s"}""".formatted(LocalDate.now().minusYears(30))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresBirthDate").value(false))
                .andReturn().getResponse().getContentAsString();

        String newAccessToken = JsonPath.read(birthDateResponse, "$.accessToken");

        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk());
    }

    @Test
    void birthDateEndpointRejectsUnderage() throws Exception {
        stubGoogleToken("underagegoogle@gmail.com", "Underage");
        String response = mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idToken":"fake-token"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(response, "$.accessToken");

        mockMvc.perform(post("/api/auth/birth-date")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"birthDate":"%s"}""".formatted(LocalDate.now().minusYears(10))))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findByEmail("underagegoogle@gmail.com").orElseThrow().getBirthDate()).isNull();
    }

    @Test
    void birthDateEndpointRejectsSecondCall() throws Exception {
        User user = createUser("alreadysetbirthdate");

        mockMvc.perform(post("/api/auth/birth-date")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"birthDate":"%s"}""".formatted(LocalDate.now().minusYears(20))))
                .andExpect(status().isConflict());
    }

    @Test
    void birthDateAndLogoutAreExemptFromGate() throws Exception {
        stubGoogleToken("exemptcheck@gmail.com", "Exempt Check");
        String response = mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idToken":"fake-token"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(response, "$.accessToken");
        String refreshToken = JsonPath.read(response, "$.refreshToken");

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refreshToken)))
                .andExpect(status().isOk());
    }
}
