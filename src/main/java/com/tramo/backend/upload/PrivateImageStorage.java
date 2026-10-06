// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.upload;

import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import com.tramo.backend.upload.entity.EditorImage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;
import java.time.Duration;
import java.util.UUID;

@Component
public class PrivateImageStorage {
    private static final java.util.concurrent.Semaphore VALIDATIONS = new java.util.concurrent.Semaphore(2);
    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final long maxBytes;

    public PrivateImageStorage(S3Client client, S3Presigner presigner,
            @Value("${app.r2.private-bucket}") String bucket,
            @Value("${app.r2.bucket}") String publicBucket,
            @Value("${app.limits.max-upload-bytes}") long maxBytes) {
        if (bucket.equals(publicBucket)) throw new IllegalArgumentException("Private images require a separate bucket");
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.maxBytes = maxBytes;
    }

    public String presignUpload(EditorImage image) {
        return presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(10))
                .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(image.temporaryKey())
                        .contentType(image.contentType()).contentLength(image.bytes()).build()).build()).url().toString();
    }

    public record ValidatedImage(String etag, String hash, boolean finalObject) {}

    public ValidatedImage validate(EditorImage image) {
        if (!VALIDATIONS.tryAcquire()) throw new RequestValidationException(RequestErrorCode.IMAGE_VALIDATION_BUSY);
        try {
            try {
                return validateObject(image, image.objectKey(), true);
            } catch (S3Exception missing) {
                if (missing.statusCode() != 404) throw missing;
                return validateObject(image, image.temporaryKey(), false);
            }
        } finally {
            VALIDATIONS.release();
        }
    }

    private ValidatedImage validateObject(EditorImage image, String key, boolean finalObject) {
        HeadObjectResponse head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        if (image.bytes() > maxBytes || head.contentLength() != image.bytes() || !image.contentType().equals(head.contentType())
                || head.eTag() == null || head.eTag().isBlank())
            throw new RequestValidationException(RequestErrorCode.IMAGE_UPLOAD_METADATA_MISMATCH);
        try (var stream = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).ifMatch(head.eTag()).build())) {
            byte[] bytes = stream.readNBytes(Math.toIntExact(image.bytes() + 1));
            if (bytes.length != image.bytes()) throw new RequestValidationException(RequestErrorCode.IMAGE_UPLOAD_SIZE_MISMATCH);
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if (image.contentHash() != null && !image.contentHash().equals(hash))
                throw new RequestValidationException(RequestErrorCode.IMAGE_UPLOAD_HASH_MISMATCH);
            ImageFileValidator.validate(bytes, image.contentType());
            return new ValidatedImage(head.eTag(), hash, finalObject);
        } catch (java.io.IOException failure) {
            throw new RequestValidationException(RequestErrorCode.IMAGE_UPLOAD_UNREADABLE, failure);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public void confirm(EditorImage image, ValidatedImage validated) {
        if (validated.finalObject()) return;
        try {
            client.copyObject(CopyObjectRequest.builder().sourceBucket(bucket).sourceKey(image.temporaryKey())
                    .destinationBucket(bucket).destinationKey(image.objectKey()).copySourceIfMatch(validated.etag())
                    .overrideConfiguration(config -> config.putHeader("cf-copy-destination-if-none-match", "*"))
                    .metadataDirective(MetadataDirective.REPLACE).contentType(image.contentType())
                    .metadata(java.util.Map.of("sha256", validated.hash())).cacheControl("private, no-store").build());
        } catch (S3Exception conflict) {
            if (conflict.statusCode() != 412) throw conflict;
            ValidatedImage existing = validateObject(image, image.objectKey(), true);
            if (!validated.hash().equals(existing.hash())) throw new RequestValidationException(RequestErrorCode.IMAGE_CONFIRMED_MISMATCH);
        }
    }

    public String presignRead(EditorImage image) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(5))
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(image.objectKey())
                        .responseCacheControl("private, no-store").build()).build()).url().toString();
    }

    public void deleteTemporary(UUID objectId) { delete("temporary/" + objectId); }
    public void deleteObject(UUID objectId) {
        deleteTemporary(objectId);
        delete("images/" + objectId);
    }
    private void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
