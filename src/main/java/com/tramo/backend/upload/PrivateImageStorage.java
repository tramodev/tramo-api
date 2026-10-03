package com.tramo.backend.upload;

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
    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public PrivateImageStorage(S3Client client, S3Presigner presigner,
            @Value("${app.r2.private-bucket}") String bucket,
            @Value("${app.r2.bucket}") String publicBucket) {
        if (bucket.equals(publicBucket)) throw new IllegalArgumentException("Private images require a separate bucket");
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    public String presignUpload(EditorImage image) {
        return presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(10))
                .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(image.temporaryKey())
                        .contentType(image.contentType()).contentLength(image.bytes()).build()).build()).url().toString();
    }

    public void complete(EditorImage image) {
        HeadObjectResponse head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(image.temporaryKey()).build());
        if (head.contentLength() != image.bytes() || !image.contentType().equals(head.contentType())) {
            throw new IllegalArgumentException("Uploaded image size or type does not match");
        }
        client.copyObject(CopyObjectRequest.builder().sourceBucket(bucket).sourceKey(image.temporaryKey())
                .destinationBucket(bucket).destinationKey(image.objectKey()).copySourceIfMatch(head.eTag())
                .metadataDirective(MetadataDirective.REPLACE).contentType(image.contentType())
                .cacheControl("private, no-store").build());
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
