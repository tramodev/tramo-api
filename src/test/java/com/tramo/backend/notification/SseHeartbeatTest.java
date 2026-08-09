package com.tramo.backend.notification;

import com.tramo.backend.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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
}
