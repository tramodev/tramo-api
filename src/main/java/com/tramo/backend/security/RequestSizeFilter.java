package com.tramo.backend.security;

import com.tramo.backend.common.ContentLimits;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > ContentLimits.REQUEST_BYTES) {
            reject(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(ContentLimits.REQUEST_BYTES + 1);
        if (body.length > ContentLimits.REQUEST_BYTES) {
            reject(response);
            return;
        }
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }

            @Override
            public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(input, request.getCharacterEncoding() == null
                        ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(request.getCharacterEncoding())));
            }
        }, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":413,\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Requests must not exceed 4 MiB.\"}");
    }
}
