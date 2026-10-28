package com.tramo.backend.auth;

import com.tramo.backend.auth.controller.AuthController;
import com.tramo.backend.auth.entity.PasswordResetToken;
import com.tramo.backend.auth.repository.PasswordResetTokenRepository;
import com.tramo.backend.auth.repository.RefreshTokenRepository;
import com.tramo.backend.auth.service.EmailService;
import com.tramo.backend.auth.service.IdentityRateLimiter;
import com.tramo.backend.auth.service.PasswordService;
import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordRecoveryDispatchTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);
    private final EmailService emails = mock(EmailService.class);
    private final IdentityRateLimiter limits = mock(IdentityRateLimiter.class);
    private final AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PasswordService passwords = new PasswordService(users, mock(PasswordEncoder.class), tokens,
            mock(RefreshTokenRepository.class), emails, limits, mock(NotificationService.class), transactions, executor);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(passwords, "entityManager", mock(EntityManager.class));
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        User known = new User();
        known.setId(1L);
        known.setEmail("known@example.test");
        when(users.findByEmail(known.getEmail())).thenReturn(Optional.of(known));
        when(users.findByIdForUpdate(known.getId())).thenReturn(Optional.of(known));
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(null, null, passwords, null, null, null)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"known@example.test", "unknown@example.test"})
    void httpResponseDoesNotWaitForAccountLookupOrTokenWrites(String email) throws Exception {
        mvc.perform(post("/api/auth/forgot-password").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isNoContent());

        verify(limits).check("forgot-password", email, 3, 3);
        verifyNoInteractions(users, tokens, transactions, emails);
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();
        verify(users).findByEmail(email);
        if (email.startsWith("known")) {
            verify(tokens).save(any(PasswordResetToken.class));
            verify(emails).sendPasswordResetEmail(any(User.class), anyString());
        } else {
            verifyNoInteractions(tokens, transactions, emails);
        }
    }

    @Test
    void rateLimitedRequestsDoNotEnqueueRecovery() {
        doThrow(new LimitExceededException("Too many attempts"))
                .when(limits).check("forgot-password", "known@example.test", 3, 3);

        assertThatThrownBy(() -> passwords.forgotPassword("known@example.test")).isInstanceOf(LimitExceededException.class);
        verifyNoInteractions(executor, users, tokens, emails);
    }

    @Test
    void workerFailureDoesNotEscapeTheBackgroundTask() {
        when(users.findByEmail("failed@example.test")).thenThrow(new IllegalStateException("Database unavailable"));
        passwords.forgotPassword("failed@example.test");
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());

        assertThatCode(task.getValue()::run).doesNotThrowAnyException();
        verifyNoInteractions(tokens, emails);
    }
}
