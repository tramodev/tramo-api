package com.tramo.backend.security;

import com.tramo.backend.security.jwt.JwtAuthFilter;
import com.tramo.backend.security.jwt.JwtService;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
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
        when(userRepository.findById(1L)).thenThrow(new DataAccessResourceFailureException("Database unavailable"));
        var request = new MockHttpServletRequest("GET", "/api/public/explore");
        request.addHeader("Authorization", "Bearer signed-token");
        var response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
        try {
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(userRepository).findById(1L);
            verifyNoInteractions(chain);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
