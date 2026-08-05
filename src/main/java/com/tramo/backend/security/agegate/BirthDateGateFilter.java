package com.tramo.backend.security.agegate;

import com.tramo.backend.user.entity.User;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

@Component
public class BirthDateGateFilter implements Filter {

    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/api/auth/birth-date", "/api/auth/logout"
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user
                && Boolean.TRUE.equals(user.getRequiresBirthDate())
                && !EXEMPT_PATHS.contains(req.getRequestURI())) {
            res.setStatus(403);
            res.setContentType("application/json");
            res.getWriter().write("""
                    {"status":403,"message":"Please provide your birth date to continue."}""");
            return;
        }
        chain.doFilter(request, response);
    }
}
