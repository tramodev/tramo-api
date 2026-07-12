package com.mypath.backend.auth.service;

import com.mypath.backend.user.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailService {
    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromAddress;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendVerificationEmail(User user, String token) {
        String verificationLink = frontendUrl + "/verify-email?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(user.getEmail());
        message.setSubject("Verify your MyPath account");
        message.setText(
                "Hi " + user.getUsername() + ",\n\n"
                        + "Click the link below to verify your email and activate your account:\n"
                        + verificationLink + "\n\n"
                        + "This link expires in 24 hours. If you didn't create this account, you can ignore this email."
        );
        try {
            mailSender.send(message);
        } catch (MailException ex) {
            // Common in dev: Resend's sandbox sender can only deliver to the account's own
            // address until a domain is verified there. Don't fail registration over it —
            // the user account is already saved by this point — log the link instead so
            // local testing isn't blocked.
            log.warn("Failed to send verification email to {}: {}. Verification link: {}",
                    user.getEmail(), ex.getMessage(), verificationLink);
        }
    }

    public void sendPasswordResetEmail(User user, String token) {
        String resetLink = frontendUrl + "/reset-password?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(user.getEmail());
        message.setSubject("Reset your MyPath password");
        message.setText(
                "Hi " + user.getUsername() + ",\n\n"
                        + "Click the link below to reset your password:\n"
                        + resetLink + "\n\n"
                        + "This link expires in 1 hour. If you didn't request this, you can ignore this email."
        );
        try {
            mailSender.send(message);
        } catch (MailException ex) {
            log.warn("Failed to send password reset email to {}: {}. Reset link: {}",
                    user.getEmail(), ex.getMessage(), resetLink);
        }
    }
}
