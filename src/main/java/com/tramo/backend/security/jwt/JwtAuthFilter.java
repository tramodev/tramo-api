// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.security.jwt;

import com.tramo.backend.common.SafeLog;
import org.slf4j.LoggerFactory;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtAuthEntryPoint authEntryPoint;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        final String token = getTokenFromRequest(request);

        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            Long userId = jwtService.getUserIdFromToken(token);
            if (userId != null) {
                User principal;
                try {
                    principal = userRepository.findById(userId).orElse(null);
                } catch (DataAccessException failure) {
                    SecurityContextHolder.clearContext();
                    String trackingId = SafeLog.failure(LoggerFactory.getLogger(JwtAuthFilter.class),
                            "authentication_unavailable", "AUTH_UNAVAILABLE", failure);
                    response.setHeader("X-Tracking-Id", trackingId);
                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Authentication unavailable");
                    return;
                }
                if (principal == null || !principal.isEnabled() || !principal.isAccountNonLocked()) {
                    SecurityContextHolder.clearContext();
                    authEntryPoint.commence(request, response, new BadCredentialsException("Invalid access token"));
                    return;
                }
                principal.setRequiresBirthDate(principal.getBirthDate() == null);
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities());

                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String getTokenFromRequest(HttpServletRequest request) {
        final String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }
}
