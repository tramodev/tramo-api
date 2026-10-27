package com.tramo.backend.security;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminAccessDeniedHttpTest extends AbstractIntegrationTest {
    private static final String KEY = "dGVzdC1iZmYtc2lnbmluZy1rZXktdGVzdC1iZmYta2V5";
    @LocalServerPort private int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void requireSignedRequests(DynamicPropertyRegistry registry) {
        registry.add("app.security.bff-signing-key", () -> KEY);
    }

    private HttpResponse<String> signedRequest(String method, String path, User user) throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String ip = "127.0.0.1";
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(KEY), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal((ip + "\n" + timestamp + "\n" + method + "\n" + path).getBytes(StandardCharsets.UTF_8)));
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", bearer(user)).header("X-Tramo-Client-IP", ip)
                .header("X-Tramo-Timestamp", timestamp).header("X-Tramo-Signature", signature)
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
                .method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void signedRegularUserRequestsReceiveJsonForbiddenWithoutAnErrorDispatch() throws Exception {
        var user = createUser("httpregular");
        var mapper = new ObjectMapper();
        for (var endpoint : List.of(Map.entry("GET", "/api/admin/reports"), Map.entry("GET", "/api/admin/users"),
                Map.entry("GET", "/api/admin/moderation-log"), Map.entry("GET", "/api/admin/unknown"),
                Map.entry("POST", "/api/admin/content/blog"), Map.entry("POST", "/api/admin/reports/103/dismiss"))) {
            String method = endpoint.getKey(); String path = endpoint.getValue();
            var response = signedRequest(method, path, user);
            assertThat(response.statusCode()).as(path).isEqualTo(403);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
            assertThat(response.headers().firstValue("X-Tracking-Id")).isPresent();
            var error = mapper.readTree(response.body());
            assertThat(error.path("status").asInt()).isEqualTo(403);
            assertThat(error.path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(error.path("message").asText()).isEqualTo("Access denied");
            assertThat(error.path("timestamp").asText()).isNotBlank();
        }
    }

    @Test
    void unsignedRequestsStillFailBackendSignatureValidation() throws Exception {
        var user = createUser("unsignedregular");
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/reports"))
                .header("Authorization", bearer(user)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("Invalid backend request");
    }
    @Test
    void rejectedPathsReturnStandardBadRequestJson() throws Exception {
        var user = createUser("rejectedpathuser");
        var mapper = new ObjectMapper();
        for (String path : List.of("/api/admin/../admin/reports", "/api/admin/reports;x=1",
                "/api/admin/reports%2Fextra", "/api/admin/reports%00", "/api/admin/reports%5Cextra")) {
            var response = signedRequest("GET", path, user);
            assertThat(response.statusCode()).as(path).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
            assertThat(response.headers().firstValue("X-Tracking-Id")).isPresent();
            var error = mapper.readTree(response.body());
            assertThat(error.path("status").asInt()).isEqualTo(400);
            assertThat(error.path("code").asText()).isEqualTo("BAD_REQUEST");
            assertThat(error.path("message").asText()).isEqualTo("Invalid request path");
            assertThat(error.path("timestamp").asText()).isNotBlank();
        }
    }

}
