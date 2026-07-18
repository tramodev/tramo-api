package com.tramo.backend.security.ratelimit;


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

        String ip = req.getRemoteAddr();
        String path = req.getRequestURI();

        if (path.startsWith("/api/auth/")) {
            Bucket bucket = switch (path) {
                case "/api/auth/login" -> rateLimiterService.resolveBucket(
                        ip + ":login", 10, 10, Duration.ofMinutes(1)
                );
                case "/api/auth/register" -> rateLimiterService.resolveBucket(
                        ip + ":register", 10, 10, Duration.ofMinutes(1)
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

