package com.tramo.backend.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tramo.backend.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SseAsyncDispatchTest extends AbstractIntegrationTest {

    private static final long SSE_TIMEOUT_MS = 1500;

    @DynamicPropertySource
    static void shortSseTimeout(DynamicPropertyRegistry registry) {
        registry.add("app.notifications.sse-timeout-ms", () -> String.valueOf(SSE_TIMEOUT_MS));
    }

    @LocalServerPort
    private int port;

    @Test
    void anExpiringNotificationStreamDoesNotTripAuthorizationOnTheAsyncDispatch() throws Exception {
        var user = createUser("ssedispatch");

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);

        try {
            HttpResponse<Stream<String>> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/notifications/stream"))
                            .header("Authorization", bearer(user))
                            .header("Accept", "text/event-stream")
                            .timeout(Duration.ofMillis(SSE_TIMEOUT_MS * 10))
                            .build(),
                    HttpResponse.BodyHandlers.ofLines());

            assertEquals(200, response.statusCode());

            List<String> lines = new ArrayList<>();
            try {
                response.body().forEach(lines::add);
            } catch (RuntimeException streamEndedAbruptly) {
                lines.add(String.valueOf(streamEndedAbruptly.getMessage()));
            }
            assertTrue(lines.stream().anyMatch(line -> line.contains("unread-count")),
                    "stream should carry the initial event");

            Thread.sleep(500);

            List<String> failures = logs.list.stream()
                    .filter(event -> describe(event).contains("response is already committed")
                            || describe(event).contains("AuthorizationDeniedException")
                            || describe(event).contains("Unhandled exception")
                            || describe(event).contains("HttpMessageNotWritableException"))
                    .map(SseAsyncDispatchTest::describe)
                    .toList();
            assertTrue(failures.isEmpty(),
                    "the async dispatch after the emitter timed out was not handled cleanly: " + failures);
        } finally {
            root.detachAppender(logs);
        }
    }

    private static String describe(ILoggingEvent event) {
        String throwable = event.getThrowableProxy() == null
                ? ""
                : event.getThrowableProxy().getClassName() + ": " + event.getThrowableProxy().getMessage();
        return event.getFormattedMessage() + " " + throwable;
    }
}
