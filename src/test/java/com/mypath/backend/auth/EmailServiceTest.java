package com.mypath.backend.auth;

import com.mypath.backend.auth.service.EmailService;
import com.mypath.backend.user.Role;
import com.mypath.backend.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EmailServiceTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(mailSender);
        ReflectionTestUtils.setField(emailService, "fromAddress", "noreply@mypath.app");
        ReflectionTestUtils.setField(emailService, "frontendUrl", "https://mypath.app");
    }

    private User user(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setRole(Role.USER);
        user.setCreatedAt(new Date());
        user.setUpdatedAt(new Date());
        return user;
    }

    @Test
    void sendVerificationEmailIncludesLinkAndRecipient() {
        emailService.sendVerificationEmail(user("alice", "alice@example.com"), "tok123");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();

        assertThat(sent.getFrom()).isEqualTo("noreply@mypath.app");
        assertThat(sent.getTo()).containsExactly("alice@example.com");
        assertThat(sent.getSubject()).isEqualTo("Verify your MyPath account");
        assertThat(sent.getText()).contains("alice").contains("https://mypath.app/verify-email?token=tok123");
    }

    @Test
    void sendPasswordResetEmailIncludesLinkAndRecipient() {
        emailService.sendPasswordResetEmail(user("bob", "bob@example.com"), "reset456");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();

        assertThat(sent.getTo()).containsExactly("bob@example.com");
        assertThat(sent.getSubject()).isEqualTo("Reset your MyPath password");
        assertThat(sent.getText()).contains("bob").contains("https://mypath.app/reset-password?token=reset456");
    }

    @Test
    void verificationEmailFailureIsSwallowedNotThrown() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> emailService.sendVerificationEmail(user("carol", "carol@example.com"), "tok"))
                .doesNotThrowAnyException();
    }

    @Test
    void passwordResetEmailFailureIsSwallowedNotThrown() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> emailService.sendPasswordResetEmail(user("dave", "dave@example.com"), "tok"))
                .doesNotThrowAnyException();
    }
}
