// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.contact;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.exception.CaptchaVerificationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContactTest extends AbstractIntegrationTest {

    private ResultActions contact(String ip, String topic, String captchaToken) throws Exception {
        return mockMvc.perform(post("/api/public/contact")
                .with(remoteAddr(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"reader@example.com","topic":"%s","message":"Hello\\nthere","captchaToken":"%s"}""".formatted(topic, captchaToken)));
    }

    @Test
    void contactVerifiesCaptchaAndSendsEmail() throws Exception {
        contact("172.16.5.1", "BUG", "contact-captcha").andExpect(status().isNoContent());
        verify(captchaVerifier).verify("contact-captcha", "contact");
        verify(emailService).sendContactEmail("reader@example.com", "BUG", "Hello\nthere");
    }

    @Test
    void contactRejectsUnknownTopicAndFailedCaptcha() throws Exception {
        contact("172.16.5.2", "SPAM", "contact-captcha").andExpect(status().isBadRequest());
        doThrow(new CaptchaVerificationException("Captcha verification failed")).when(captchaVerifier).verify("bad-captcha", "contact");
        contact("172.16.5.2", "OTHER", "bad-captcha").andExpect(status().isForbidden());
        verify(emailService, never()).sendContactEmail(anyString(), anyString(), anyString());
    }

    @Test
    void contactIsRateLimitedPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            contact("172.16.5.3", "FEEDBACK", "contact-captcha").andExpect(status().isNoContent());
        }
        contact("172.16.5.3", "FEEDBACK", "contact-captcha").andExpect(status().isTooManyRequests());
        contact("172.16.5.4", "FEEDBACK", "contact-captcha").andExpect(status().isNoContent());
    }
}
