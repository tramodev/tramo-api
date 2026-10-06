// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.security;

import com.tramo.backend.security.jwt.JwtAuthFilter;
import com.tramo.backend.security.jwt.JwtService;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthFilterFailureTest {
    @Test
    void databaseFailureNeverFallsBackToTokenClaimsOrAnonymousAccess() throws Exception {
        JwtService jwtService = mock(JwtService.class);
        UserRepository userRepository = mock(UserRepository.class);
        FilterChain chain = mock(FilterChain.class);
        JwtAuthFilter filter = new JwtAuthFilter();
        ReflectionTestUtils.setField(filter, "jwtService", jwtService);
        ReflectionTestUtils.setField(filter, "userRepository", userRepository);
        when(jwtService.getUserIdFromToken("signed-token")).thenReturn(1L);
        when(userRepository.findById(1L)).thenThrow(new DataAccessResourceFailureException("password=DATABASE_SECRET person@example.test select private_content from users"));
        var request = new MockHttpServletRequest("GET", "/api/public/explore");
        request.addHeader("Authorization", "Bearer signed-token");
        var response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
        Logger logger = (Logger) LoggerFactory.getLogger(JwtAuthFilter.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(503);
            String trackingId = response.getHeader("X-Tracking-Id");
            assertThat(UUID.fromString(trackingId).toString()).isEqualTo(trackingId);
            assertThat(response.getErrorMessage()).isEqualTo("Authentication unavailable");
            assertThat(logs.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel().toString()).isEqualTo("ERROR");
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).contains("AUTH_UNAVAILABLE", trackingId,
                                "DataAccessResourceFailureException")
                        .doesNotContain("DATABASE_SECRET", "person@example.test", "private_content", "signed-token");
            });
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(userRepository).findById(1L);
            verifyNoInteractions(chain);
        } finally {
            logger.detachAppender(logs);
            logs.stop();
            SecurityContextHolder.clearContext();
        }
    }
}
