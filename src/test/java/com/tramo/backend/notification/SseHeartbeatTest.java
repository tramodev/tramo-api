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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SseHeartbeatTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void fastHeartbeat(DynamicPropertyRegistry registry) {
        registry.add("app.notifications.sse-heartbeat-ms", () -> "300");
        registry.add("app.notifications.sse-timeout-ms", () -> "60000");
    }

    @LocalServerPort
    private int port;

    @Test
    void anIdleStreamKeepsReceivingHeartbeatsSoProxiesDoNotTimeItOut() throws Exception {
        var user = createUser("sseheartbeat");

        HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/notifications/stream"))
                        .header("Authorization", bearer(user))
                        .header("Accept", "text/event-stream")
                        .timeout(Duration.ofSeconds(30))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());

        assertEquals(200, response.statusCode());

        int heartbeats = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while (heartbeats < 2 && (line = reader.readLine()) != null) {
                if (line.startsWith(":")) {
                    heartbeats++;
                }
            }
        }

        assertTrue(heartbeats >= 2, "expected the idle stream to emit heartbeat comments, got " + heartbeats);
    }

    @Test
    void aClientThatVanishesIsDroppedOnTheFirstFailedHeartbeat() throws Exception {
        var user = createUser("ssevanished");

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoLinger(true, 0);
            socket.getOutputStream().write(("GET /api/notifications/stream HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Authorization: " + bearer(user) + "\r\n"
                    + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            boolean live = false;
            while (!live && (line = reader.readLine()) != null) {
                live = line.contains("unread-count");
            }
            assertTrue(live, "the stream should have delivered its initial event before we kill it");

            root.addAppender(logs);
        }

        try {
            Thread.sleep(1500);

            List<String> failures = logs.list.stream()
                    .map(SseHeartbeatTest::describe)
                    .filter(line -> line.contains("Unhandled exception")
                            || line.contains("AsyncRequestNotUsable")
                            || line.contains("HttpMessageNotWritableException"))
                    .toList();
            assertTrue(failures.isEmpty(),
                    "a vanished client should be dropped silently, not retried every heartbeat: " + failures);
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
