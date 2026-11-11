// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth.service;

import com.tramo.backend.common.SafeLog;
import com.tramo.backend.user.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.util.HtmlUtils;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Service
public class EmailService {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private static final Map<String, String> TEMPLATES = new ConcurrentHashMap<>();

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromAddress;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${app.mail.contact}")
    private String contactAddress;

    @Value("${app.mail.enabled:true}")
    private boolean mailEnabled;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Async
    public void sendVerificationEmail(User user, String token) {
        String verificationLink = frontendUrl + "/verify-email?token=" + token;

        if (!mailEnabled) {
            log.info("event=verification_email_skipped code=MAIL_DISABLED");
            return;
        }

        String text = "Hi " + user.getUsername() + ",\n\n"
                + "Click the link below to verify your email and activate your account:\n"
                + verificationLink + "\n\n"
                + "This link expires in 24 hours. If you didn't create this account, you can ignore this email.";
        String html = actionHtml("Verify your email", "Confirm your email to activate your Tramo account.", user.getUsername(),
                "Click the button below to verify your email and activate your account.", "Verify my email", verificationLink,
                "This link expires in 24 hours. If you didn\u2019t create this account, you can ignore this email.");
        try {
            mailSender.send(message(user.getEmail(), "Verify your Tramo account", text, html));
        } catch (MailException | MessagingException ex) {
            SafeLog.failure(log, "verification_email_failed", "MAIL_SEND_FAILED", ex);
        }
    }

    public void sendModerationEmail(String email, String username, String notice) {
        if (!mailEnabled) {
            log.info("event=moderation_email_skipped code=MAIL_DISABLED");
            return;
        }
        String text = "Hi " + username + ",\n\n" + notice;
        String content = render("notice", Map.of("title", "Moderation notice", "username", escape(username),
                "message", escape(notice).replace("\n", "<br>")));
        String html = layoutHtml("Moderation notice", "A moderator reviewed your Tramo account.", content);
        try { mailSender.send(message(email, "Tramo moderation notice", text, html)); }
        catch (MailException | MessagingException failure) { SafeLog.failure(log, "moderation_email_failed", "MAIL_SEND_FAILED", failure); }
    }

    public void sendContactEmail(String email, String topic, String body) {
        if (!mailEnabled) {
            log.info("event=contact_email_skipped code=MAIL_DISABLED");
            return;
        }
        String text = "From: " + email + "\nTopic: " + topic + "\n\n" + body;
        String content = render("notice", Map.of("title", "Contact: " + escape(topic), "username", escape(contactAddress),
                "message", escape(text).replace("\n", "<br>")));
        String html = layoutHtml("Contact message", "New message from the Tramo contact form.", content);
        try {
            MimeMessage message = message(contactAddress, "[Contact] " + topic + " from " + email, text, html);
            message.setReplyTo(InternetAddress.parse(email));
            mailSender.send(message);
        } catch (MessagingException failure) {
            throw new MailPreparationException(failure);
        }
    }

    @Async
    public void sendPasswordResetEmail(User user, String token) {
        String resetLink = frontendUrl + "/reset-password?token=" + token;

        if (!mailEnabled) {
            log.info("event=password_reset_email_skipped code=MAIL_DISABLED");
            return;
        }

        String text = "Hi " + user.getUsername() + ",\n\n"
                + "Click the link below to reset your password:\n"
                + resetLink + "\n\n"
                + "This link expires in 1 hour. If you didn't request this, you can ignore this email.";
        String html = actionHtml("Reset your password", "Use this link to choose a new Tramo password.", user.getUsername(),
                "Click the button below to reset your password.", "Reset my password", resetLink,
                "This link expires in 1 hour. If you didn\u2019t request this, you can ignore this email.");
        try {
            mailSender.send(message(user.getEmail(), "Reset your Tramo password", text, html));
        } catch (MailException | MessagingException ex) {
            SafeLog.failure(log, "password_reset_email_failed", "MAIL_SEND_FAILED", ex);
        }
    }

    private MimeMessage message(String to, String subject, String text, String html) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(fromAddress);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(text, html);
        return message;
    }

    private String actionHtml(String title, String preheader, String username, String message,
                              String buttonLabel, String buttonUrl, String note) {
        String content = render("action", Map.of("title", escape(title), "username", escape(username),
                "message", escape(message), "buttonLabel", escape(buttonLabel), "buttonUrl", escape(buttonUrl),
                "note", escape(note)));
        return layoutHtml(title, preheader, content);
    }

    private String layoutHtml(String title, String preheader, String content) {
        return render("layout", Map.of("title", escape(title), "preheader", escape(preheader),
                "frontendUrl", escape(frontendUrl), "year", String.valueOf(Year.now().getValue()), "content", content));
    }

    private static String render(String template, Map<String, String> values) {
        return PLACEHOLDER.matcher(TEMPLATES.computeIfAbsent(template, EmailService::load))
                .replaceAll(match -> java.util.regex.Matcher.quoteReplacement(values.getOrDefault(match.group(1), "")));
    }

    private static String load(String template) {
        try {
            return new ClassPathResource("mail/" + template + ".html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
