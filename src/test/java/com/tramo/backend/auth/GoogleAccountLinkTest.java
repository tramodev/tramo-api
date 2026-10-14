package com.tramo.backend.auth;

import com.tramo.backend.auth.service.GoogleAuthService;
import com.tramo.backend.auth.service.GoogleTokenVerifier;
import com.tramo.backend.auth.service.IdentityRateLimiter;
import com.tramo.backend.auth.service.SessionService;
import com.tramo.backend.exception.RequestValidationException;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GoogleAccountLinkTest {
    private final UserRepository users = mock(UserRepository.class);
    private final GoogleTokenVerifier verifier = mock(GoogleTokenVerifier.class);
    private final SessionService sessions = mock(SessionService.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final IdentityRateLimiter rateLimiter = mock(IdentityRateLimiter.class);
    private final GoogleAuthService service = new GoogleAuthService(users, verifier, sessions, passwords, rateLimiter);
    private final User user = new User();

    @BeforeEach
    void setUp() {
        user.setEmail("existing@example.com");
        user.setEmailVerified(true);
        user.setPassword("encoded-password");
        user.setRole(Role.USER);
        when(verifier.verify("token")).thenReturn(new GoogleTokenVerifier.GoogleTokenPayload("subject", user.getEmail(), "Existing"));
        when(users.findByEmailForUpdate(user.getEmail())).thenReturn(Optional.of(user));
        when(users.save(user)).thenReturn(user);
    }

    @Test
    void emailMatchWithoutPasswordDoesNotLinkOrIssueSession() {
        assertThatThrownBy(() -> service.googleAuth("token", null)).isInstanceOf(RequestValidationException.class);
        assertThat(user.getGoogleSub()).isNull();
        verifyNoInteractions(sessions);
        verify(users, never()).save(any());
    }

    @Test
    void linkingRequiresCorrectPasswordAndIsRateLimited() {
        assertThatThrownBy(() -> service.googleAuth("token", "wrong")).isInstanceOf(RequestValidationException.class);
        verify(rateLimiter).check("google-link", user.getEmail(), 10, 10);
        verifyNoInteractions(sessions);
        assertThat(user.getGoogleSub()).isNull();

        when(passwords.matches("correct", "encoded-password")).thenReturn(true);
        service.googleAuth("token", "correct");
        assertThat(user.getGoogleSub()).isEqualTo("subject");
        verify(sessions).issueSession(user);
    }

    @Test
    void unverifiedAccountIsNotActivatedEvenWithCorrectPassword() {
        user.setEmailVerified(false);
        assertThatThrownBy(() -> service.googleAuth("token", "correct")).isInstanceOf(RequestValidationException.class);
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getGoogleSub()).isNull();
        verifyNoInteractions(sessions, passwords);
    }

    @Test
    void legacyGoogleOnlyAccountRequiresRecoveryInsteadOfEmailOnlyLinking() {
        user.setPassword(null);
        assertThatThrownBy(() -> service.googleAuth("token", "anything")).isInstanceOf(RequestValidationException.class);
        assertThat(user.getGoogleSub()).isNull();
        verifyNoInteractions(sessions);
    }

    @Test
    void emailMatchDoesNotReplaceExistingLink() {
        user.setGoogleSub("different-subject");
        assertThatThrownBy(() -> service.googleAuth("token", "correct")).isInstanceOf(RequestValidationException.class);
        assertThat(user.getGoogleSub()).isEqualTo("different-subject");
        verifyNoInteractions(sessions, passwords);
    }

    @Test
    void linkedSubjectDoesNotDependOnEmailOrPassword() {
        user.setEmail("changed@example.com");
        user.setGoogleSub("subject");
        when(users.findByGoogleSub("subject")).thenReturn(Optional.of(user));
        service.googleAuth("token", null);
        verify(sessions).issueSession(user);
        verify(users, never()).findByEmailForUpdate(any());
        verifyNoInteractions(passwords);
    }

    @Test
    void bannedLinkedAccountCannotReceiveSession() {
        user.setBanned(true);
        when(users.findByGoogleSub("subject")).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.googleAuth("token", null)).isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(sessions);
    }
}
