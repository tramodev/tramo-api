package com.tramo.backend.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class R2Client {
    private static final Logger log = LoggerFactory.getLogger(R2Client.class);

    private final S3Presigner presigner;
    private final S3Client client;
    private final String bucket;
    private final String publicBaseUrl;
    private final Pattern editorImageUrlPattern;

    public R2Client(
            @Value("${app.r2.account-id}") String accountId,
            @Value("${app.r2.access-key}") String accessKey,
            @Value("${app.r2.secret-key}") String secretKey,
            @Value("${app.r2.bucket}") String bucket,
            @Value("${app.r2.public-base-url}") String publicBaseUrl
    ) {
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        URI endpoint = URI.create("https://" + accountId + ".r2.cloudflarestorage.com");
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(Region.of("auto"))
                .credentialsProvider(credentials)
                .build();
        this.client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of("auto"))
                .credentialsProvider(credentials)
                .build();
        this.editorImageUrlPattern = Pattern.compile(
                Pattern.quote(this.publicBaseUrl + "/editor-image/") + "[\\w\\-/]+\\.(?:jpg|jpeg|png|webp|gif)"
        );
    }

    public String presignPut(String key, String contentType) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .putObjectRequest(putObjectRequest)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);
        return presigned.url().toString();
    }

    public String publicUrlFor(String key) {
        return publicBaseUrl + "/" + key;
    }

    public Set<String> extractReferencedUrls(String content) {
        Set<String> urls = new LinkedHashSet<>();
        if (content == null || content.isBlank()) {
            return urls;
        }
        Matcher matcher = editorImageUrlPattern.matcher(content);
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        return urls;
    }

    public void deleteByPublicUrl(String url) {
        if (url == null || !url.startsWith(publicBaseUrl + "/")) {
            return;
        }
        String key = url.substring(publicBaseUrl.length() + 1);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception e) {
            log.warn("Failed to delete orphaned R2 object {}", key, e);
        }
    }
}
