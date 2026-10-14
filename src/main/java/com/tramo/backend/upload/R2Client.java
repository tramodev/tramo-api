// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.upload;

import com.tramo.backend.common.SafeLog;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.upload.repository.PublicUploadRepository;
import com.tramo.backend.upload.repository.PendingImageDeletionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;

import java.util.Map;
import java.util.Date;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class R2Client {
    private static final Logger log = LoggerFactory.getLogger(R2Client.class);

    private final S3Client client;
    private final String bucket;
    private final String privateBucket;
    private final String publicBaseUrl;
    private final Pattern editorImageUrlPattern;
    private final UploadRecordRepository uploadRecordRepository;
    private final PublicUploadRepository publicUploads;
    private final PendingImageDeletionRepository references;
    private final TransactionTemplate transactions;

    public R2Client(
            S3Client client,
            @Value("${app.r2.bucket}") String bucket,
            @Value("${app.r2.private-bucket}") String privateBucket,
            @Value("${app.r2.public-base-url}") String publicBaseUrl,
            UploadRecordRepository uploadRecordRepository,
            PublicUploadRepository publicUploads,
            PendingImageDeletionRepository references,
            PlatformTransactionManager transactionManager
    ) {
        this.uploadRecordRepository = uploadRecordRepository;
        this.publicUploads = publicUploads;
        this.references = references;
        this.transactions = new TransactionTemplate(transactionManager);
        this.bucket = bucket;
        this.privateBucket = privateBucket;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        this.client = client;
        this.editorImageUrlPattern = Pattern.compile(
                Pattern.quote(this.publicBaseUrl + "/editor-image/") + "[\\w\\-/]+\\.(?:jpg|jpeg|png|webp|gif)"
        );
    }

    public void publishValidated(String key, String contentType, PrivateImageStorage.ValidatedImage validated) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                .contentLength((long) validated.bytes().length).metadata(Map.of("sha256", validated.hash()))
                .cacheControl("public, max-age=31536000, immutable").build(), RequestBody.fromBytes(validated.bytes()));
    }

    public String publicUrlFor(String key) {
        return publicBaseUrl + "/" + key;
    }

    public boolean isOwnedUrl(String url, String expectedKind, Long ownerId) {
        if (url == null || !url.startsWith(publicBaseUrl + "/")) {
            return false;
        }
        String key = url.substring(publicBaseUrl.length() + 1);
        String[] parts = key.split("/", 3);
        return parts.length == 3 && parts[0].equals(expectedKind) && parts[1].equals(String.valueOf(ownerId))
                && uploadRecordRepository.findConfirmedPublicKey(key, ownerId).isPresent();
    }

    public boolean isFromOurDomain(String url) {
        return url != null && url.startsWith(publicBaseUrl + "/");
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

    public boolean isExportableUrl(String url) {
        return isFromOurDomain(url) && url.substring(publicBaseUrl.length() + 1)
                .matches("(?:editor-image|thumbnail)/[0-9]+/[\\w-]+\\.(?:jpg|jpeg|png|webp|gif)");
    }

    public java.io.InputStream openForExport(String url) {
        if (!isExportableUrl(url)) throw new IllegalArgumentException("Unsupported storage reference");
        return client.getObject(software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                .bucket(bucket).key(url.substring(publicBaseUrl.length() + 1)).build());
    }

    public boolean isOwnedPublicUrl(String url, Long ownerId) {
        return ownerId != null && isFromOurDomain(url) && url.substring(publicBaseUrl.length() + 1)
                .matches("(?:editor-image|avatar|banner|thumbnail)/" + ownerId + "/[\\w-]+\\.(?:jpg|jpeg|png|webp|gif)");
    }

    public boolean deleteByPublicUrl(String url, Long ownerId) {
        if (!isOwnedPublicUrl(url, ownerId)) {
            return false;
        }
        String key = url.substring(publicBaseUrl.length() + 1);
        try {
            var uploadId = uploadRecordRepository.findPublicUploadIdByObjectKey(key);
            boolean claimed = Boolean.TRUE.equals(transactions.execute(status -> {
                var upload = uploadId.flatMap(publicUploads::findForUpdate).orElse(null);
                if (uploadId.isPresent() && (upload == null || !upload.getOwnerId().equals(ownerId))) return false;
                var pending = references.findByUrl(url);
                Date cutoff = new Date(System.currentTimeMillis() - Duration.ofDays(1).toMillis());
                if (pending.isPresent() && pending.get().getRequestedAt().after(cutoff)) return false;
                if (references.isReferencedGlobally(url)) return false;
                if (upload != null) upload.setState("DELETING");
                return true;
            }));
            if (!claimed) return false;
            uploadId.ifPresent(id -> client.deleteObject(
                    DeleteObjectRequest.builder().bucket(privateBucket).key("temporary/" + id).build()));
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            
            uploadRecordRepository.deleteByObjectKey(key);
            return true;
        } catch (Exception e) {
            SafeLog.failure(log, "r2_orphan_delete_failed", "STORAGE_DELETE_FAILED", e);
            return false;
        }
    }
}
