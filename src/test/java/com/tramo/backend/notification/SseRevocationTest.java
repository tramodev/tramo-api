package com.tramo.backend.notification;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.auth.service.PasswordService;
import com.tramo.backend.moderation.service.ModerationService;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SseRevocationTest extends AbstractIntegrationTest {
    @Autowired PasswordService passwords;
    @Autowired ModerationService moderation;
    @Autowired NotificationService notifications;
    @Autowired PlatformTransactionManager transactionManager;

    private MvcResult stream(User user) throws Exception {
        return mockMvc.perform(get("/api/notifications/stream").header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
    }

    @Test
    void passwordChangeClosesAnOpenStreamAfterCommit() throws Exception {
        User user = createUser("ssepasswordchange");
        MvcResult stream = stream(user);
        passwords.changePassword(user, "Passw0rd123!", "NewPassw0rd!");
        mockMvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
        mockMvc.perform(get("/api/notifications/stream").header("Authorization", bearer(user)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordResetClosesAnOpenStreamAfterCommit() throws Exception {
        User user = createUser("ssepasswordreset");
        MvcResult stream = stream(user);
        passwords.forgotPassword(user.getEmail());
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailService, org.mockito.Mockito.timeout(5000)).sendPasswordResetEmail(any(User.class), token.capture());
        passwords.resetPassword(token.getValue(), "NewPassw0rd!");
        mockMvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
    }

    @Test
    void banningAnAccountClosesItsOpenStreamsAfterCommit() throws Exception {
        User user = createUser("ssebanned");
        MvcResult stream = stream(user);
        moderation.banUser(user.getId(), createAdmin("sseadmin"), "test");
        mockMvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
    }

    @Test
    void aChangeFromAnotherInstanceIsDetectedOnTheNextHeartbeat() throws Exception {
        User user = createUser("sseremoterevocation");
        MvcResult stream = stream(user);
        jdbcTemplate.update("UPDATE users SET session_version = ? WHERE id = ?", UUID.randomUUID(), user.getId());
        notifications.sendHeartbeat();
        mockMvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
    }

    @Test
    void rolledBackRevocationDoesNotCloseTheStream() throws Exception {
        User user = createUser("sserollback");
        MvcResult stream = stream(user);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            passwords.changePassword(user, "Passw0rd123!", "NewPassw0rd!");
            status.setRollbackOnly();
        });
        assertThat(userRepository.findById(user.getId()).orElseThrow().getSessionVersion()).isNull();
        notifications.sendHeartbeat();
        assertThat(stream.getRequest().isAsyncStarted()).isTrue();
        notifications.revokeSessionsAfterCommit(user.getId());
        mockMvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
    }
}
