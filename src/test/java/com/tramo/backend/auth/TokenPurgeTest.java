package com.tramo.backend.auth;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.auth.entity.EmailVerificationToken;
import com.tramo.backend.auth.entity.PasswordResetToken;
import com.tramo.backend.auth.repository.EmailVerificationTokenRepository;
import com.tramo.backend.auth.repository.PasswordResetTokenRepository;
import com.tramo.backend.auth.service.AuthService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenPurgeTest extends AbstractIntegrationTest {

    @Autowired
    EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    AuthService authService;

    @Test
    void purgeDeletesOnlyExpiredVerificationAndResetTokens() {
        User user = createUser("purgeabletokens");

        EmailVerificationToken expiredVerification = new EmailVerificationToken();
        expiredVerification.setUser(user);
        expiredVerification.setToken(UUID.randomUUID().toString());
        expiredVerification.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        emailVerificationTokenRepository.save(expiredVerification);

        EmailVerificationToken liveVerification = new EmailVerificationToken();
        liveVerification.setUser(user);
        liveVerification.setToken(UUID.randomUUID().toString());
        liveVerification.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        emailVerificationTokenRepository.save(liveVerification);

        PasswordResetToken expiredReset = new PasswordResetToken();
        expiredReset.setUser(user);
        expiredReset.setToken(UUID.randomUUID().toString());
        expiredReset.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        passwordResetTokenRepository.save(expiredReset);

        PasswordResetToken liveReset = new PasswordResetToken();
        liveReset.setUser(user);
        liveReset.setToken(UUID.randomUUID().toString());
        liveReset.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        passwordResetTokenRepository.save(liveReset);

        authService.purgeExpiredTokens();

        assertThat(emailVerificationTokenRepository.findByToken(expiredVerification.getToken())).isEmpty();
        assertThat(emailVerificationTokenRepository.findByToken(liveVerification.getToken())).isPresent();
        assertThat(passwordResetTokenRepository.findByToken(expiredReset.getToken())).isEmpty();
        assertThat(passwordResetTokenRepository.findByToken(liveReset.getToken())).isPresent();
    }
}
