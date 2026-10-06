// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tramo.backend.auth.service.EmailService;
import com.tramo.backend.auth.dto.ResetPasswordRequestDTO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.tramo.backend.common.SafeLog;
import com.tramo.backend.subscription.patreon.PatreonController;
import org.springframework.transaction.PlatformTransactionManager;
import com.tramo.backend.common.SafeLoggingConfiguration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.upload.R2Client;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.MethodArgumentNotValidException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SensitiveLoggingTest {
    private static final String SECRET = "password=PASSWORD_SECRET Authorization: Bearer JWT_SECRET "
            + "Cookie: refresh=REFRESH_SECRET smtp=SMTP_SECRET person@example.test PRIVATE_BODY "
            + "https://private.test/image?X-Amz-Signature=SIGNED_SECRET token=RECOVERY_SECRET";
    private final Logger logger = (Logger) LoggerFactory.getLogger("com.tramo.backend");
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void capture() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void unexpectedHttpFailureRetainsSafeDiagnosticAndServerTrackingId() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var response = mvc.perform(get("/failure?token=RECOVERY_SECRET")
                        .header("Authorization", "Bearer JWT_SECRET").header("Cookie", "refresh=REFRESH_SECRET")
                        .header("X-Tracking-Id", "CLIENT_SECRET"))
                .andExpect(status().isInternalServerError()).andReturn().getResponse();
        String trackingId = response.getHeader("X-Tracking-Id");
        assertThat(UUID.fromString(trackingId).toString()).isEqualTo(trackingId);
        assertThat(response.getContentAsString()).contains("An unexpected error occurred");
        assertSafe(response.getContentAsString());
        assertThat(logText()).contains("event=request_failed", "code=INTERNAL_ERROR", trackingId,
                "exceptionType=java.lang.IllegalStateException", "exceptionType=java.lang.RuntimeException",
                "exceptionType=java.sql.SQLException", "SensitiveLoggingTest$FailingController.failure:");
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        assertSafe(logText());
    }

    @Test
    void badRequestDoesNotEchoExceptionMessages() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var response = mvc.perform(get("/invalid")).andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(response.getContentAsString()).contains("Invalid request");
        assertSafe(response.getContentAsString());
        assertSafe(logText());
    }

    @Test
    void smtpFailuresAndDisabledDeliveryNeverLogRecipientsOrLinks() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new MailSendException(SECRET, new RuntimeException(SECRET))).when(sender).send(any(SimpleMailMessage.class));
        EmailService service = new EmailService(sender);
        ReflectionTestUtils.setField(service, "frontendUrl", "https://private.test");
        ReflectionTestUtils.setField(service, "fromAddress", "from@example.test");
        ReflectionTestUtils.setField(service, "mailEnabled", true);
        User user = new User();
        user.setEmail("person@example.test");
        user.setUsername("PRIVATE_NAME");
        service.sendVerificationEmail(user, "VERIFICATION_SECRET");
        service.sendPasswordResetEmail(user, "RECOVERY_SECRET");
        ReflectionTestUtils.setField(service, "mailEnabled", false);
        service.sendVerificationEmail(user, "VERIFICATION_SECRET");
        service.sendPasswordResetEmail(user, "RECOVERY_SECRET");
        assertThat(logText()).contains("verification_email_failed", "password_reset_email_failed", "MAIL_SEND_FAILED",
                "org.springframework.mail.MailSendException", "trackingId=", "MAIL_DISABLED");
        assertSafe(logText());
    }

    @Test
    void cyclicCausesAndSuppressedFailuresDoNotLeakOrLoop() {
        RuntimeException first = new RuntimeException(SECRET);
        RuntimeException second = new RuntimeException(SECRET, first);
        first.initCause(second);
        first.addSuppressed(new RuntimeException(SECRET));
        SafeLog.failure(logger, "storage_failed", "STORAGE_DELETE_FAILED", first);
        assertThat(logText()).contains("storage_failed", "java.lang.RuntimeException");
        assertSafe(logText());
    }

    @Test
    void r2DeletionFailureOmitsSignedUrlObjectKeyAndProviderMessage() {
        S3Client client = mock(S3Client.class);
        doThrow(S3Exception.builder().message(SECRET).statusCode(403).build())
                .when(client).deleteObject(any(DeleteObjectRequest.class));
        R2Client r2 = new R2Client(null, client, "bucket", "https://private.test",
                mock(UploadRecordRepository.class));
        r2.deleteByPublicUrl("https://private.test/PRIVATE_NAME?X-Amz-Signature=SIGNED_SECRET");
        assertThat(logText()).contains("r2_orphan_delete_failed", "STORAGE_DELETE_FAILED", "upstreamStatus=403",
                "software.amazon.awssdk.services.s3.model.S3Exception", "trackingId=");
        assertSafe(logText());
    }

    @Test
    void providerFailureNestedInAuthErrorDoesNotEscapeToHttp() {
        var provider = HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, SECRET,
                null, SECRET.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        var response = new GlobalExceptionHandler().handleInvalidToken(new InvalidTokenException(SECRET, provider));
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid or expired token");
        assertThat(logText()).contains("request_rejected", "code=UNAUTHORIZED", "upstreamStatus=401", "trackingId=");
        assertSafe(logText());
        assertSafe(response.getBody().getMessage());
    }

    @Test
    void validationOmitsRejectedValuesAndInterpolatedMessages() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "password", SECRET, false, null, null, SECRET));
        var exception = new MethodArgumentNotValidException(
                new MethodParameter(FailingController.class.getDeclaredMethod("invalid"), -1), binding);
        var response = new GlobalExceptionHandler().handleValidationExceptions(exception);
        assertThat(response.getBody().getErrors()).containsEntry("password", "Invalid value");
        assertSafe(response.getBody().getErrors().toString());
        assertSafe(logText());
    }

    @Test
    void scheduledFailureIsSafeAndDoesNotStopRecurringTasks() throws Exception {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        new SafeLoggingConfiguration().safeScheduledFailures().customize(scheduler);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.initialize();
        CountDownLatch executions = new CountDownLatch(2);
        try {
            var task = scheduler.scheduleAtFixedRate(() -> {
                executions.countDown();
                throw new IllegalStateException(SECRET, new RuntimeException(SECRET, new java.sql.SQLException("select password from users " + SECRET)));
            }, Duration.ofMillis(10));
            assertThat(executions.await(5, TimeUnit.SECONDS)).isTrue();
            task.cancel(false);
        } finally {
            scheduler.shutdown();
        }
        assertThat(logText()).contains("scheduled_task_failed", "INTERNAL_ERROR", "trackingId=", "IllegalStateException");
        assertSafe(logText());
    }

    @Test
    void oauthCallbackDoesNotLogQueryTokensOrProviderErrorDescription() throws Exception {
        PatreonController controller = new PatreonController(mock(PlatformTransactionManager.class),
                null, null, null, null, "client", "https://callback.test", "https://tramo.test");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var response = mvc.perform(get("/api/auth/patreon/callback")
                        .param("code", "CODE_SECRET").param("state", "STATE_SECRET")
                        .param("error", "ACCESS_SECRET").param("error_description", SECRET))
                .andExpect(status().isFound()).andReturn().getResponse();
        String trackingId = response.getHeader("X-Tracking-Id");
        assertThat(UUID.fromString(trackingId).toString()).isEqualTo(trackingId);
        assertThat(response.getHeader("Location")).isEqualTo("https://tramo.test/settings?tab=plan&patreon=error");
        assertThat(logText()).contains("patreon_callback_rejected", "OAUTH_DENIED", trackingId);
        assertSafe(logText());
        assertSafe(response.getContentAsString());
    }

    @Test
    void codedBusinessFailurePreservesGuidanceWithoutExposingItsCause() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var response = mvc.perform(get("/publish"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROJECT_DESCRIPTION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Add a description before publishing"))
                .andReturn().getResponse();
        assertThat(logText()).contains("code=PROJECT_DESCRIPTION_REQUIRED", response.getHeader("X-Tracking-Id"));
        assertSafe(response.getContentAsString());
        assertSafe(logText());
    }

    @Test
    void realBeanValidationResolvesCodesInsteadOfEchoingPasswordsAndTokens() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var response = mvc.perform(post("/validate").contentType("application/json")
                        .content("{\"token\":\"RECOVERY_SECRET\",\"newPassword\":\"PASSWORD_SECRET\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errorCodes.newPassword").value("PASSWORD_FORMAT_INVALID"))
                .andExpect(jsonPath("$.errors.newPassword").value(
                        "Password must contain at least one uppercase letter, one number, and one symbol"))
                .andReturn().getResponse();
        assertSafe(response.getContentAsString());
        assertSafe(logText());
    }

    private String logText() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
    }

    private void assertSafe(String text) {
        assertThat(text).doesNotContain("PASSWORD_SECRET", "JWT_SECRET", "REFRESH_SECRET", "SMTP_SECRET",
                "person@example.test", "PRIVATE_BODY", "SIGNED_SECRET", "RECOVERY_SECRET", "VERIFICATION_SECRET",
                "PRIVATE_NAME", "CLIENT_SECRET", "CODE_SECRET", "STATE_SECRET", "ACCESS_SECRET",
                "https://private.test", "Authorization", "Cookie");
    }

    @RestController
    static class FailingController {
        @GetMapping("/failure")
        public void failure() {
            throw new IllegalStateException(SECRET, new RuntimeException(SECRET, new java.sql.SQLException("select password from users " + SECRET)));
        }

        @GetMapping("/publish")
        public void publish() {
            throw new RequestValidationException(RequestErrorCode.PROJECT_DESCRIPTION_REQUIRED, new RuntimeException(SECRET));
        }

        @PostMapping("/validate")
        public void validate(@Valid @RequestBody ResetPasswordRequestDTO request) {
        }

        @GetMapping("/invalid")
        public void invalid() {
            throw new IllegalArgumentException(SECRET);
        }
    }
}
