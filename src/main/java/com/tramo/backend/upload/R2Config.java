package com.tramo.backend.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

@Configuration
public class R2Config {

    @Bean
    public S3Presigner r2Presigner(@Value("${app.r2.account-id}") String accountId,
                                   @Value("${app.r2.access-key}") String accessKey,
                                   @Value("${app.r2.secret-key}") String secretKey) {
        return S3Presigner.builder()
                .endpointOverride(endpoint(accountId))
                .region(Region.of("auto"))
                .credentialsProvider(credentials(accessKey, secretKey))
                .build();
    }

    @Bean
    public S3Client r2S3Client(@Value("${app.r2.account-id}") String accountId,
                               @Value("${app.r2.access-key}") String accessKey,
                               @Value("${app.r2.secret-key}") String secretKey) {
        return S3Client.builder()
                .endpointOverride(endpoint(accountId))
                .region(Region.of("auto"))
                .credentialsProvider(credentials(accessKey, secretKey))
                .overrideConfiguration(o -> o
                        .apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(3)))
                .build();
    }

    private URI endpoint(String accountId) {
        return URI.create("https://" + accountId + ".r2.cloudflarestorage.com");
    }

    private StaticCredentialsProvider credentials(String accessKey, String secretKey) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }
}
