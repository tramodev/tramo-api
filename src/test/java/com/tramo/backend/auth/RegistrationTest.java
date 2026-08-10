package com.tramo.backend.auth;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.auth.repository.EmailVerificationTokenRepository;
import com.tramo.backend.exception.CaptchaVerificationException;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistrationTest extends AbstractIntegrationTest {

    @Autowired
    EmailVerificationTokenRepository emailVerificationTokenRepository;

    private String registerJson(String username, String email, String password) {
        return registerJson(username, email, password, "1990-01-01");
    }

    private String registerJson(String username, String email, String password, String birthDate) {
        return """
                {"username":"%s","email":"%s","password":"%s","captchaToken":"test-token","birthDate":"%s"}""".formatted(username, email, password, birthDate);
    }

    private org.springframework.test.web.servlet.ResultActions register(String body) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .with(uniqueIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void registerCreatesUnverifiedUserAndSendsVerificationEmail() throws Exception {
        register(registerJson("newuser", "newuser@example.com", "Passw0rd123!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        User user = userRepository.findByUsernameIgnoreCase("newuser").orElseThrow();
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.getPassword()).isNotEqualTo("Passw0rd123!");
        assertThat(emailVerificationTokenRepository.findAll())
                .anyMatch(t -> t.getUser().getId().equals(user.getId()));
        verify(emailService).sendVerificationEmail(any(User.class), anyString());
    }

    @Test
    void registerRejectsFailedCaptcha() throws Exception {
        doThrow(new CaptchaVerificationException("Captcha verification failed"))
                .when(captchaVerifier).verify(anyString(), anyString());

        register(registerJson("captchafail", "captchafail@example.com", "Passw0rd123!"))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findByUsernameIgnoreCase("captchafail")).isEmpty();
    }

    @Test
    void registerRejectsMissingCaptchaToken() throws Exception {
        register("""
                {"username":"nocaptcha","email":"nocaptcha@example.com","password":"Passw0rd123!"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.captchaToken").exists());
    }

    @Test
    void registerRejectsDuplicateUsername() throws Exception {
        createUser("taken");
        register(registerJson("taken", "other@example.com", "Passw0rd123!"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.username").exists());
    }

    @Test
    void registerRejectsDuplicateUsernameCaseInsensitive() throws Exception {
        createUser("MixedCase");
        register(registerJson("mixedcase", "other@example.com", "Passw0rd123!"))
                .andExpect(status().isConflict());
    }

    @Test
    void registerRespondsIdenticallyToSuccessOnDuplicateEmailWithoutCreatingAnAccount() throws Exception {
        createUser("existing", "shared@example.com", true, false, Role.USER);

        register(registerJson("someoneelse", "shared@example.com", "Passw0rd123!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Account created. Check your email to verify your account."));

        assertThat(userRepository.findByUsernameIgnoreCase("someoneelse")).isEmpty();
        verify(emailService, never()).sendVerificationEmail(any(), anyString());
    }

    @Test
    void emailHasADatabaseLevelUniqueConstraint() {
        createUser("dbconstraintowner", "dbconstraint@example.com", true, false, Role.USER);

        User duplicate = new User();
        duplicate.setUsername("dbconstraintother");
        duplicate.setEmail("dbconstraint@example.com");
        duplicate.setPassword("irrelevant");
        duplicate.setRole(Role.USER);
        duplicate.setVisibility(true);
        duplicate.setCreatedAt(new java.util.Date());
        duplicate.setUpdatedAt(new java.util.Date());
        duplicate.setEmailVerified(true);

        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void registerRejectsInvalidUsernameCharacters() throws Exception {
        register(registerJson("bad name!", "a@example.com", "Passw0rd123!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username").exists());
    }

    @Test
    void registerRejectsTooShortUsername() throws Exception {
        register(registerJson("ab", "a@example.com", "Passw0rd123!"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerRejectsPasswordWithoutSymbolOrNumber() throws Exception {
        register(registerJson("validname", "a@example.com", "Password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void registerRejectsTooShortPassword() throws Exception {
        register(registerJson("validname", "a@example.com", "P1!"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerRejectsInvalidEmail() throws Exception {
        register(registerJson("validname", "notanemail", "Passw0rd123!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    void registerIgnoresClientSuppliedRole() throws Exception {
        register("""
                {"username":"sneaky","email":"sneaky@example.com","password":"Passw0rd123!","role":"ADMIN","captchaToken":"test-token","birthDate":"1990-01-01"}""")
                .andExpect(status().isOk());

        assertThat(userRepository.findByUsernameIgnoreCase("sneaky").orElseThrow().getRole())
                .isEqualTo(Role.USER);
    }

    @Test
    void registerDoesNotSendEmailWhenValidationFails() throws Exception {
        register(registerJson("validname", "notanemail", "Passw0rd123!"))
                .andExpect(status().isBadRequest());
        verify(emailService, never()).sendVerificationEmail(any(), anyString());
    }

    @Test
    void checkUsernameReportsAvailability() throws Exception {
        createUser("occupied");

        mockMvc.perform(get("/api/auth/check-username").param("username", "occupied"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));

        mockMvc.perform(get("/api/auth/check-username").param("username", "OCCUPIED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));

        mockMvc.perform(get("/api/auth/check-username").param("username", "freename"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));

        mockMvc.perform(get("/api/auth/check-username").param("username", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
    }
}
