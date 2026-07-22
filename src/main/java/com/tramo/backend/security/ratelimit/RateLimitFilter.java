package com.tramo.backend.security.ratelimit;


import com.tramo.backend.security.ClientIp;
import io.github.bucket4j.Bucket;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

@Component
public class RateLimitFilter implements Filter {

    private final RateLimiterService rateLimiterService;

    public RateLimitFilter(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String ip = ClientIp.from(req);
        String path = req.getRequestURI();

        if (path.startsWith("/api/auth/")) {
            Bucket bucket = switch (path) {
                case "/api/auth/login" -> rateLimiterService.resolveBucket(
                        ip + ":login", 10, 10, Duration.ofMinutes(1)
                );
                case "/api/auth/register" -> rateLimiterService.resolveBucket(
                        ip + ":register", 10, 10, Duration.ofMinutes(1)
                );
                // email-triggering endpoints: tight, to prevent mail bombing
                case "/api/auth/forgot-password" -> rateLimiterService.resolveBucket(
                        ip + ":forgot-password", 3, 3, Duration.ofMinutes(1)
                );
                case "/api/auth/resend-verification" -> rateLimiterService.resolveBucket(
                        ip + ":resend-verification", 3, 3, Duration.ofMinutes(1)
                );
                // token-consuming endpoints: limit brute force
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
                // availability probes: cap enumeration harvesting (client debounces)
                case "/api/auth/check-email", "/api/auth/check-username" -> rateLimiterService.resolveBucket(
                        ip + ":check", 30, 30, Duration.ofMinutes(1)
                );
                default -> null;
            };

            if (bucket != null && !bucket.tryConsume(1)) {
                res.setStatus(429);
                res.setContentType("application/json");
                res.getWriter().write("""
                        {"status":429,"message":"Rate limit exceeded. Try again later."}""");
                return;
            }
        }

        chain.doFilter(request, response);
    }
}

