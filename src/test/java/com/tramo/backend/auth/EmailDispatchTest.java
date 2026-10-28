package com.tramo.backend.auth;

import com.tramo.backend.auth.service.EmailService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class EmailDispatchTest {
    @Configuration
    @EnableAsync
    static class Config {
        @Bean JavaMailSender mailSender() { return mock(JavaMailSender.class); }
        @Bean EmailService emails(JavaMailSender sender) { return new EmailService(sender); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mailDeliveryDoesNotBlockTheCaller(boolean verification) throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "app.mail.from", "noreply@tramo.test", "app.frontend-url", "https://tramo.test", "app.mail.enabled", "true")));
            context.register(Config.class);
            context.refresh();
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            doAnswer(invocation -> {
                started.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Mail delivery timed out");
                return null;
            }).when(context.getBean(JavaMailSender.class)).send(any(SimpleMailMessage.class));
            var callers = Executors.newSingleThreadExecutor();
            try {
                User user = new User();
                user.setEmail("alice@tramo.test");
                user.setUsername("alice");
                var returned = callers.submit(() -> {
                    var emails = context.getBean(EmailService.class);
                    if (verification) emails.sendVerificationEmail(user, "token");
                    else emails.sendPasswordResetEmail(user, "token");
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                returned.get(1, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                callers.shutdownNow();
            }
        }
    }
}
