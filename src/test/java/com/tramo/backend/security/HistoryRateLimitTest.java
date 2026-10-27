package com.tramo.backend.security;

import com.tramo.backend.security.ratelimit.RateLimitFilter;
import com.tramo.backend.security.ratelimit.RateLimiterService;
import com.tramo.backend.user.entity.User;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HistoryRateLimitTest {
    @Test
    void historyReadsShareTheirOwnLimitWithoutConsumingAutosaveOrSnapshotWrites() throws Exception {
        var user = new User(); user.setId(1L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        var filter = new RateLimitFilter(new RateLimiterService(), mock(ClientIp.class));
        try {
            for (int index = 0; index < 60; index++) {
                assertThat(request(filter, "GET", index % 2 == 0 ? "/api/project/abc/history" : "/api/project/abc/history/1")).isEqualTo(200);
            }
            assertThat(request(filter, "GET", "/api/project/abc/history")).isEqualTo(429);
            assertThat(request(filter, "PUT", "/api/item/1/content")).isEqualTo(200);
            assertThat(request(filter, "POST", "/api/project/abc/history")).isEqualTo(200);
            for (int index = 1; index < 60; index++) assertThat(request(filter, "PUT", "/api/item/1/content")).isEqualTo(200);
            assertThat(request(filter, "PUT", "/api/item/1/content")).isEqualTo(429);
        } finally { SecurityContextHolder.clearContext(); }
    }

    private int request(RateLimitFilter filter, String method, String path) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(method, path), response, mock(FilterChain.class));
        return response.getStatus();
    }
}
