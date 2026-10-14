package com.tramo.backend.security;

import com.tramo.backend.common.ContentLimits;
import com.tramo.backend.common.Pagination;
import com.tramo.backend.exception.PayloadTooLargeException;
import com.tramo.backend.exception.RequestValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class ResourceLimitsTest {
    @Test
    void noteLimitCountsUtf8BytesNotCharacters() {
        assertThatCode(() -> ContentLimits.assertNote("a".repeat(ContentLimits.NOTE_BYTES))).doesNotThrowAnyException();
        assertThatCode(() -> ContentLimits.assertNote("é".repeat(ContentLimits.NOTE_BYTES / 2))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ContentLimits.assertNote("é".repeat(ContentLimits.NOTE_BYTES / 2 + 1)))
                .isInstanceOf(PayloadTooLargeException.class);
        assertThatThrownBy(() -> ContentLimits.assertNote("a".repeat(ContentLimits.NOTE_BYTES + 1)))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void paginationRejectsUnboundedSizesAndOverflow() {
        assertThat(Pagination.page(0, 100).getPageSize()).isEqualTo(100);
        for (int size : new int[]{0, -1, 101, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> Pagination.page(0, size)).isInstanceOf(RequestValidationException.class);
        }
        assertThatThrownBy(() -> Pagination.page(-1, 10)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> Pagination.page(Integer.MAX_VALUE, 100)).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void requestLimitRejectsKnownLengthBeforeReading() throws Exception {
        var request = new MockHttpServletRequest("PUT", "/api/item/1/content") {
            @Override public long getContentLengthLong() { return ContentLimits.REQUEST_BYTES + 1L; }
            @Override public jakarta.servlet.ServletInputStream getInputStream() { throw new AssertionError("Body must not be read"); }
        };
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new RequestSizeFilter().doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void unknownLengthCannotBypassRequestLimit() throws Exception {
        var request = new MockHttpServletRequest("PUT", "/api/item/1/content") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        request.setContent(new byte[ContentLimits.REQUEST_BYTES + 1]);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new RequestSizeFilter().doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void boundedRequestBodyRemainsReadableAndExactLimitIsAccepted() throws Exception {
        byte[] body = "é".repeat(ContentLimits.REQUEST_BYTES / 2).getBytes(StandardCharsets.UTF_8);
        var request = new MockHttpServletRequest("POST", "/api/webhooks/patreon");
        request.setContent(body);
        var chain = new MockFilterChain();
        new RequestSizeFilter().doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo(body);
    }
}
