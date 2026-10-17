package com.tramo.backend.auth;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.auth.repository.PasswordResetTokenRepository;
import com.tramo.backend.auth.service.PasswordService;
import com.tramo.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.annotation.DirtiesContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordRecoveryConcurrencyTest extends AbstractIntegrationTest {
    @MockitoSpyBean UserRepository users;
    @Autowired PasswordService passwords;
    @Autowired PasswordResetTokenRepository tokens;

    @Test
    void forgotRequestThatObservedOldCredentialsCannotIssueAfterPasswordChange() throws Exception {
        var user = createUser("forgotrace");
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = users.findById(user.getId());
            observed.countDown();
            if (!proceed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
            return result;
        }).when(users).findByEmail(user.getEmail());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var forgot = executor.submit(() -> passwords.forgotPassword(user.getEmail()));
            assertThat(observed.await(10, TimeUnit.SECONDS)).isTrue();
            passwords.changePassword(user, "Passw0rd123!", "ChangedPassw0rd!");
            proceed.countDown();
            forgot.get(10, TimeUnit.SECONDS);
            assertThat(tokens.findAll()).isEmpty();
            verify(emailService, never()).sendPasswordResetEmail(any(), any());
        } finally {
            proceed.countDown();
            executor.shutdownNow();
        }
    }
}
