// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth;

import com.tramo.backend.auth.service.EmailService;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.BodyPart;
import jakarta.mail.Part;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailServiceTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
        emailService = new EmailService(mailSender);
        ReflectionTestUtils.setField(emailService, "fromAddress", "noreply@tramo.dev");
        ReflectionTestUtils.setField(emailService, "frontendUrl", "https://tramo.dev");
        ReflectionTestUtils.setField(emailService, "mailEnabled", true);
        ReflectionTestUtils.setField(emailService, "contactAddress", "contact@tramo.dev");
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
    void sendVerificationEmailIncludesLinkAndRecipient() throws Exception {
        emailService.sendVerificationEmail(user("alice", "alice@example.com"), "tok123");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getFrom()[0].toString()).isEqualTo("noreply@tramo.dev");
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("alice@example.com");
        assertThat(sent.getSubject()).isEqualTo("Verify your Tramo account");
        assertThat(part(sent, "text/plain")).contains("alice").contains("https://tramo.dev/verify-email?token=tok123");
        assertThat(part(sent, "text/html")).contains("alice").contains("href=\"https://tramo.dev/verify-email?token=tok123\"")
                .contains("Verify my email").contains("https://tramo.dev/email-logo.png").contains("https://tramo.dev/privacy");
    }

    @Test
    void sendPasswordResetEmailIncludesLinkAndRecipient() throws Exception {
        emailService.sendPasswordResetEmail(user("bob", "bob@example.com"), "reset456");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("bob@example.com");
        assertThat(sent.getSubject()).isEqualTo("Reset your Tramo password");
        assertThat(part(sent, "text/plain")).contains("bob").contains("https://tramo.dev/reset-password?token=reset456");
        assertThat(part(sent, "text/html")).contains("bob").contains("href=\"https://tramo.dev/reset-password?token=reset456\"").contains("Reset my password");
    }

    @Test
    void verificationEmailFailureIsSwallowedNotThrown() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        assertThatCode(() -> emailService.sendVerificationEmail(user("carol", "carol@example.com"), "tok"))
                .doesNotThrowAnyException();
    }

    @Test
    void passwordResetEmailFailureIsSwallowedNotThrown() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        assertThatCode(() -> emailService.sendPasswordResetEmail(user("dave", "dave@example.com"), "tok"))
                .doesNotThrowAnyException();
    }
    @Test
    void moderationEmailContainsTheNoticeAndHonorsDisabledMail() throws Exception {
        emailService.sendModerationEmail("alice@example.com", "alice", "Your account has been banned. Reason: Abuse");
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getAllRecipients()[0].toString()).isEqualTo("alice@example.com");
        assertThat(part(captor.getValue(), "text/plain")).contains("alice", "Your account has been banned. Reason: Abuse");
        assertThat(part(captor.getValue(), "text/html")).contains("alice", "Your account has been banned. Reason: Abuse");
        org.mockito.Mockito.clearInvocations(mailSender);
        ReflectionTestUtils.setField(emailService, "mailEnabled", false);
        emailService.sendModerationEmail("alice@example.com", "alice", "Notice");
        org.mockito.Mockito.verifyNoInteractions(mailSender);
    }

    @Test
    void failedModerationEmailDoesNotFailTheCommittedAction() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));
        assertThatCode(() -> emailService.sendModerationEmail("alice@example.com", "alice", "Notice")).doesNotThrowAnyException();
    }

    @Test
    void contactEmailGoesToContactAddressWithReplyToSenderAndPropagatesFailures() throws Exception {
        emailService.sendContactEmail("reader@example.com", "BUG", "<script>x</script>");
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("contact@tramo.dev");
        assertThat(sent.getReplyTo()[0].toString()).isEqualTo("reader@example.com");
        assertThat(sent.getSubject()).isEqualTo("[Contact] BUG from reader@example.com");
        assertThat(part(sent, "text/plain")).contains("reader@example.com", "<script>x</script>");
        assertThat(part(sent, "text/html")).doesNotContain("<script>");
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));
        assertThatThrownBy(() -> emailService.sendContactEmail("reader@example.com", "BUG", "Hi")).isInstanceOf(MailSendException.class);
    }

    @Test
    void htmlBodyEscapesUserControlledValues() throws Exception {
        emailService.sendModerationEmail("alice@example.com", "<b>alice</b>", "Reason: <script>alert(1)</script>\n{{content}}");
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        String html = part(captor.getValue(), "text/html");
        assertThat(html).doesNotContain("<script>").doesNotContain("<b>alice</b>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;").contains("&lt;b&gt;alice&lt;/b&gt;").contains("{{content}}");
    }

    private static String part(Part part, String type) throws Exception {
        if (part instanceof MimeMessage message) message.saveChanges();
        if (part.isMimeType(type)) return (String) part.getContent();
        if (part.isMimeType("multipart/*")) {
            MimeMultipart multipart = (MimeMultipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                String found = part(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
