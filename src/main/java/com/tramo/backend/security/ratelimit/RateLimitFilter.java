// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.security.ratelimit;


import com.tramo.backend.security.ClientIp;
import com.tramo.backend.user.entity.User;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

@Component
public class RateLimitFilter implements Filter {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");
    private static final Set<String> TIGHT_TIER_PATHS = Set.of(
            "/user/password", "/user/me", "/api/uploads/presign", "/api/uploads/editor-images/presign",
            "/api/subscription/patreon/sync", "/api/project/draft-commit"
    );

    private final RateLimiterService rateLimiterService;
    private final ClientIp clientIp;

    public RateLimitFilter(RateLimiterService rateLimiterService, ClientIp clientIp) {
        this.rateLimiterService = rateLimiterService;
        this.clientIp = clientIp;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        if (clientIp.requiresSignature(req) && !clientIp.isTrusted(req)) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"status\":401,\"message\":\"Invalid backend request\"}");
            return;
        }

        if (clientIp.isPublicCacheRequest(req) && clientIp.isTrusted(req)) {
            chain.doFilter(request, response);
            return;
        }

        String ip = clientIp.from(req);
        String path = req.getRequestURI();

        if (path.startsWith("/api/public/") && path.endsWith("/editor-images/resolve")) {
            Bucket bucket = rateLimiterService.resolveBucket(ip + ":image-resolve", 120, 120, Duration.ofMinutes(1));
            if (!tryConsumeOrReject(bucket, res)) return;
        }

        if ("GET".equals(req.getMethod()) && path.startsWith("/api/project/")
                && (path.endsWith("/map-preview") || path.endsWith("/editor"))) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof User user) {
                String action = path.endsWith("/editor") ? "editor" : "map-preview";
                Bucket bucket = rateLimiterService.resolveBucket("user:" + action + ":" + user.getId(), 30, 30, Duration.ofMinutes(1));
                if (!tryConsumeOrReject(bucket, res)) return;
            }
        }

        if ("POST".equals(req.getMethod()) && path.matches("/api/project/[^/]+/fork")) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof User user) {
                Bucket bucket = rateLimiterService.resolveBucket("user:fork:" + user.getId(), 10, 10, Duration.ofMinutes(1));
                if (!tryConsumeOrReject(bucket, res)) return;
            }
        }

        if (path.startsWith("/api/auth/")) {
            Bucket bucket = switch (path) {
                case "/api/auth/login" -> rateLimiterService.resolveBucket(
                        ip + ":login", 10, 10, Duration.ofMinutes(1)
                );
                case "/api/auth/register" -> rateLimiterService.resolveBucket(
                        ip + ":register", 10, 10, Duration.ofMinutes(1)
                );
                
                case "/api/auth/forgot-password" -> rateLimiterService.resolveBucket(
                        ip + ":forgot-password", 3, 3, Duration.ofMinutes(1)
                );
                case "/api/auth/resend-verification" -> rateLimiterService.resolveBucket(
                        ip + ":resend-verification", 3, 3, Duration.ofMinutes(1)
                );
                
                case "/api/auth/reset-password" -> rateLimiterService.resolveBucket(
                        ip + ":reset-password", 10, 10, Duration.ofMinutes(1)
                );
                case "/api/auth/verify-email" -> rateLimiterService.resolveBucket(
                        ip + ":verify-email", 10, 10, Duration.ofMinutes(1)
                );
                case "/api/auth/google" -> rateLimiterService.resolveBucket(
                        ip + ":google", 10, 10, Duration.ofMinutes(1)
                );
                case "/api/auth/refresh" -> rateLimiterService.resolveBucket(
                        ip + ":refresh", 30, 30, Duration.ofMinutes(1)
                );
                
                case "/api/auth/check-username" -> rateLimiterService.resolveBucket(
                        ip + ":check", 30, 30, Duration.ofMinutes(1)
                );
                default -> null;
            };

            if (bucket != null && !tryConsumeOrReject(bucket, res)) {
                return;
            }
        } else if ((MUTATING_METHODS.contains(req.getMethod()) || ("GET".equals(req.getMethod()) && path.matches("/api/project/[^/]+/history(?:/[^/]+)?"))) && !path.startsWith("/api/admin/")) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof User user) {
                boolean historyRead = "GET".equals(req.getMethod()) && path.matches("/api/project/[^/]+/history(?:/[^/]+)?");
                boolean tight = MUTATING_METHODS.contains(req.getMethod()) && path.matches("/api/project/[^/]+/history(?:/[^/]+/restore)?") || path.endsWith("/report") || TIGHT_TIER_PATHS.contains(path)
                        || path.matches("/api/uploads/[0-9a-fA-F-]+/complete");
                Bucket bucket = historyRead
                        ? rateLimiterService.resolveBucket("user:history:" + user.getId(), 60, 60, Duration.ofMinutes(1))
                        : tight
                        ? rateLimiterService.resolveBucket("user:tight:" + (path.equals("/api/project/draft-commit") ? "draft-commit:" : "") + user.getId(), 10, 10, Duration.ofMinutes(1))
                        : rateLimiterService.resolveBucket("user:default:" + user.getId(), 60, 60, Duration.ofMinutes(1));

                if (!tryConsumeOrReject(bucket, res)) {
                    return;
                }
            }
        } else if (path.startsWith("/api/public/") && "GET".equals(req.getMethod())) {
            Bucket bucket = rateLimiterService.resolveBucket(ip + ":public", 120, 120, Duration.ofMinutes(1));
            if (!tryConsumeOrReject(bucket, res)) {
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean tryConsumeOrReject(Bucket bucket, HttpServletResponse res) throws IOException {
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return true;
        }
        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000);
        res.setStatus(429);
        res.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        res.setContentType("application/json");
        res.getWriter().write("""
                {"status":429,"message":"Rate limit exceeded. Try again later."}""");
        return false;
    }
}
