package com.tramo.backend.upload;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class R2ConfigTest {
    @Test
    void bothClientsUseTheConfiguredEndpoint() {
        URI endpoint = URI.create("http://localhost:9000");
        var config = new R2Config();
        try (var client = config.r2S3Client(endpoint, "access", "secret");
             var presigner = config.r2Presigner(endpoint, "access", "secret")) {
            assertThat(client.serviceClientConfiguration().endpointOverride()).contains(endpoint);
            var request = GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(GetObjectRequest.builder().bucket("local-bucket").key("image.png").build()).build();
            assertThat(presigner.presignGetObject(request).url().toString())
                    .startsWith("http://localhost:9000/local-bucket/image.png?");
        }
    }
}
