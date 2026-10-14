package com.tramo.backend.upload;

import com.jayway.jsonpath.JsonPath;
import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.upload.entity.UploadRecord;
import com.tramo.backend.upload.repository.PublicUploadRepository;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.upload.service.PublicUploadService;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.upload.repository.PendingImageDeletionRepository;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublicUploadTest extends AbstractIntegrationTest {
    private static final String PRIVATE = "test-private-bucket", PUBLIC = "test-bucket";
    private static final byte[] PNG = image();
    private static final String HASH = hash(PNG);
    private record Stored(byte[] bytes, String type, String etag) {}
    private final Map<String, Stored> stored = new ConcurrentHashMap<>();
    @Autowired PublicUploadRepository uploads;
    @Autowired UploadRecordRepository records;
    @Autowired PublicUploadService service;
    @Autowired R2Client publicStorage;
    @Autowired ItemService items;
    @Autowired PendingImageDeletionRepository pending;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    private static byte[] image() {
        try {
            var output = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private Stored object(String bucket, String key) {
        Stored object = stored.get(bucket + ":" + key);
        if (object == null) throw NoSuchKeyException.builder().statusCode(404).build();
        return object;
    }

    @BeforeEach
    void storage() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            HeadObjectRequest request = call.getArgument(0);
            Stored object = object(request.bucket(), request.key());
            return HeadObjectResponse.builder().contentLength((long) object.bytes().length).contentType(object.type()).eTag(object.etag()).build();
        });
        when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(call -> {
            GetObjectRequest request = call.getArgument(0);
            Stored object = object(request.bucket(), request.key());
            if (!Objects.equals(request.ifMatch(), object.etag())) throw S3Exception.builder().statusCode(412).build();
            return new ResponseInputStream<>(GetObjectResponse.builder().build(), new ByteArrayInputStream(object.bytes()));
        });
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> publish(call.getArgument(0), call.getArgument(1)));
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> delete(call.getArgument(0)));
    }

    private PutObjectResponse publish(PutObjectRequest request, RequestBody body) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try (var input = body.contentStreamProvider().newStream()) {
            byte[] bytes = input.readAllBytes();
            assertThat((long) bytes.length).isEqualTo(request.contentLength());
            stored.put(request.bucket() + ":" + request.key(), new Stored(bytes, request.contentType(), UUID.randomUUID().toString()));
        }
        return PutObjectResponse.builder().build();
    }

    private DeleteObjectResponse delete(DeleteObjectRequest request) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        stored.remove(request.bucket() + ":" + request.key());
        return DeleteObjectResponse.builder().build();
    }

    private ResultActions presign(User user, String kind, String type, String hash, long bytes) throws Exception {
        return mockMvc.perform(post("/api/uploads/presign").header("Authorization", bearer(user))
                .contentType("application/json").content("""
                        {"kind":"%s","contentType":"%s","contentHash":"%s","contentBytes":%d}
                        """.formatted(kind, type, hash, bytes)));
    }

    private UUID begin(User user, String kind) throws Exception {
        String response = presign(user, kind, "image/png", HASH, PNG.length).andExpect(status().isOk())
                .andExpect(jsonPath("$.publicUrl").doesNotExist()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.uploadId"));
    }

    private void stage(UUID id, byte[] bytes, String type) {
        stored.put(PRIVATE + ":temporary/" + id, new Stored(bytes, type, UUID.randomUUID().toString()));
    }

    private ResultActions complete(User user, UUID id) throws Exception {
        return mockMvc.perform(post("/api/uploads/" + id + "/complete").header("Authorization", bearer(user)));
    }

    private UploadRecord record(UUID id) { return records.findById(uploads.findById(id).orElseThrow().getUploadRecordId()).orElseThrow(); }
    private void expire(UUID id) { jdbcTemplate.update("UPDATE public_upload SET requested_at = ? WHERE id = ?", new Date(System.currentTimeMillis() - Duration.ofDays(2).toMillis()), id); }

    private String publicUrl(UUID id) { return publicStorage.publicUrlFor(record(id).getObjectKey()); }
    private void expireDeletion(String url) {
        jdbcTemplate.update("UPDATE pending_image_deletion SET requested_at = ? WHERE url = ?",
                new Date(System.currentTimeMillis() - Duration.ofDays(2).toMillis()), url);
    }

    @Test
    void onlyValidatedFilesBecomeAssignablePublicUrlsAndConfirmationIsIdempotent() throws Exception {
        User owner = createUser("publicupload");
        UUID id = begin(owner, "avatar");
        String expected = publicStorage.publicUrlFor(record(id).getObjectKey());
        mockMvc.perform(put("/api/profile/me").header("Authorization", bearer(owner)).contentType("application/json")
                        .content("{\"imageUrl\":\"" + expected + "\"}"))
                .andExpect(status().isBadRequest());
        stage(id, PNG, "image/png");
        complete(owner, id).andExpect(status().isOk()).andExpect(jsonPath("$.publicUrl").value(expected));
        mockMvc.perform(put("/api/profile/me").header("Authorization", bearer(owner)).contentType("application/json")
                        .content("{\"imageUrl\":\"" + expected + "\"}"))
                .andExpect(status().isOk());
        complete(owner, id).andExpect(status().isOk()).andExpect(jsonPath("$.publicUrl").value(expected));
        presign(owner, "avatar", "image/png", HASH, PNG.length).andExpect(status().isOk()).andExpect(jsonPath("$.uploadUrl").doesNotExist());
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(records.sumStorageBytesByUserId(owner.getId())).isEqualTo(PNG.length);
        var published = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(published.capture(), any(RequestBody.class));
        assertThat(published.getValue().bucket()).isEqualTo(PUBLIC);
        assertThat(published.getValue().metadata()).containsEntry("sha256", HASH);
        assertThat(published.getValue().cacheControl()).contains("immutable");
    }

    @Test
    void falseHashRealSizeAndRealFormatAreRejectedBeforePublication() throws Exception {
        User owner = createUser("invalidpublicupload");
        String wrong = presign(owner, "thumbnail", "image/png", "0".repeat(64), PNG.length).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID wrongHash = UUID.fromString(JsonPath.read(wrong, "$.uploadId"));
        stage(wrongHash, PNG, "image/png");
        complete(owner, wrongHash).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_UPLOAD_HASH_MISMATCH"));
        UUID wrongSize = begin(owner, "avatar");
        stage(wrongSize, Arrays.copyOf(PNG, PNG.length - 1), "image/png");
        complete(owner, wrongSize).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_UPLOAD_METADATA_MISMATCH"));
        String fakeType = presign(owner, "thumbnail", "image/jpeg", HASH, PNG.length).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID wrongType = UUID.fromString(JsonPath.read(fakeType, "$.uploadId"));
        stage(wrongType, PNG, "image/jpeg");
        complete(owner, wrongType).andExpect(status().isBadRequest());
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(stored.keySet()).noneMatch(key -> key.startsWith(PUBLIC + ":"));
    }

    @Test
    void truncatedDownloadIsRejectedEvenWhenMetadataClaimsTheCorrectSize() throws Exception {
        User owner = createUser("truncatedpublicupload");
        UUID id = begin(owner, "avatar"); stage(id, PNG, "image/png");
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(),
                new ByteArrayInputStream(Arrays.copyOf(PNG, PNG.length - 1))));
        complete(owner, id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_UPLOAD_SIZE_MISMATCH"));
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void confirmationRequiresTheOwnerAndAnUploadedFile() throws Exception {
        User owner = createUser("publicowner"), stranger = createUser("publicstranger");
        UUID id = begin(owner, "avatar");
        complete(stranger, id).andExpect(status().isForbidden());
        complete(owner, id).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/uploads/" + id + "/complete")).andExpect(status().isUnauthorized());
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void aReusedHashCannotShrinkItsReservationOrChangeItsType() throws Exception {
        User owner = createUser("publicreservation");
        UUID id = begin(owner, "avatar");
        presign(owner, "avatar", "image/png", HASH, 1).andExpect(status().isBadRequest());
        presign(owner, "avatar", "image/jpeg", HASH, PNG.length).andExpect(status().isBadRequest());
        assertThat(record(id).getBytes()).isEqualTo(PNG.length);
        assertThat(records.count()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateReservationsChargeOnlyOnce() throws Exception {
        User owner = createUser("publicduplicate");
        var executor = Executors.newFixedThreadPool(4);
        try {
            var attempts = java.util.stream.IntStream.range(0, 4).mapToObj(i -> executor.submit(() -> begin(owner, "avatar"))).toList();
            Set<UUID> ids = new HashSet<>();
            for (var attempt : attempts) ids.add(attempt.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(records.sumStorageBytesByUserId(owner.getId())).isEqualTo(PNG.length);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void concurrentDifferentFilesCannotReserveTheSameRemainingQuota() throws Exception {
        User owner = createUser("publicatomic");
        UploadRecord used = new UploadRecord(); used.setUserId(owner.getId()); used.setObjectKey("reserved"); used.setBytes(524288000 - PNG.length); records.save(used);
        var executor = Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var a = executor.submit(() -> { start.await(); return presign(owner, "avatar", "image/png", HASH, PNG.length).andReturn().getResponse().getStatus(); });
            var b = executor.submit(() -> { start.await(); return presign(owner, "avatar", "image/png", "0".repeat(64), PNG.length).andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(List.of(a.get(10, java.util.concurrent.TimeUnit.SECONDS), b.get(10, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 429);
            assertThat(records.sumStorageBytesByUserId(owner.getId())).isEqualTo(524288000);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void overwrittenTemporaryObjectsCannotChangeThePublishedFile() throws Exception {
        User owner = createUser("publicimmutable");
        UUID id = begin(owner, "avatar"); stage(id, PNG, "image/png");
        complete(owner, id).andExpect(status().isOk());
        String key = record(id).getObjectKey();
        stage(id, "not an image".getBytes(), "image/png");
        complete(owner, id).andExpect(status().isOk());
        assertThat(object(PUBLIC, key).bytes()).isEqualTo(PNG);
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void sourceReplacementAfterValidationCannotChangeThePublishedBytes() throws Exception {
        User owner = createUser("publicetag");
        UUID id = begin(owner, "avatar"); stage(id, PNG, "image/png");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            stage(id, "changed".getBytes(), "image/png");
            return publish(call.getArgument(0), call.getArgument(1));
        });
        complete(owner, id).andExpect(status().isOk());
        assertThat(object(PUBLIC, record(id).getObjectKey()).bytes()).isEqualTo(PNG);
    }

    @Test
    void abandonedReservationsKeepQuotaOnDeletionFailureAndReleaseItAfterRetry() throws Exception {
        User owner = createUser("publicabandoned");
        UUID id = begin(owner, "avatar"); stage(id, PNG, "image/png"); expire(id);
        doThrow(new IllegalStateException("R2 unavailable")).doAnswer(call -> delete(call.getArgument(0)))
                .when(s3Client).deleteObject(any(DeleteObjectRequest.class));
        service.purge();
        assertThat(uploads.findById(id).orElseThrow().getState()).isEqualTo("DELETING");
        assertThat(records.sumStorageBytesByUserId(owner.getId())).isEqualTo(PNG.length);
        service.purge();
        assertThat(uploads.existsById(id)).isFalse();
        assertThat(records.sumStorageBytesByUserId(owner.getId())).isZero();
        assertThat(stored).isEmpty();
    }

    @Test
    void readyUploadsLoseOnlyTheirExpiredTemporaryObject() throws Exception {
        User owner = createUser("publictemporary");
        UUID id = begin(owner, "avatar"); stage(id, PNG, "image/png"); complete(owner, id).andExpect(status().isOk()); expire(id);
        pending.deleteAll();
        service.purge();
        assertThat(pending.existsByUrl(publicUrl(id))).isTrue();
        assertThat(uploads.findById(id).orElseThrow().isTemporaryCleaned()).isTrue();
        assertThat(stored).doesNotContainKey(PRIVATE + ":temporary/" + id);
        assertThat(object(PUBLIC, record(id).getObjectKey()).bytes()).isEqualTo(PNG);
        assertThat(records.sumStorageBytesByUserId(owner.getId())).isEqualTo(PNG.length);
    }

    @Test
    void publicDeletionAlsoRemovesItsTemporaryObjectBeforeDroppingTheReservation() throws Exception {
        User owner = createUser("publicdelete");
        UUID id = begin(owner, "avatar"); stage(id, PNG, "image/png"); complete(owner, id).andExpect(status().isOk());
        expireDeletion(publicUrl(id));
        assertThat(publicStorage.deleteByPublicUrl(publicStorage.publicUrlFor(record(id).getObjectKey()), owner.getId())).isTrue();
        assertThat(stored).isEmpty();
        assertThat(uploads.existsById(id)).isFalse();
    }

    @Test
    void confirmedButUnassignedThumbnailsAreReclaimedAfterGrace() throws Exception {
        User owner = createUser("unusedthumbnail");
        UUID id = begin(owner, "thumbnail"); stage(id, PNG, "image/png"); complete(owner, id).andExpect(status().isOk());
        String url = publicUrl(id);
        assertThat(pending.existsByUrl(url)).isTrue();
        assertThat(publicStorage.deleteByPublicUrl(url, owner.getId())).isFalse();
        items.purgePendingImageDeletions();
        assertThat(uploads.existsById(id)).isTrue();
        expireDeletion(url);
        items.purgePendingImageDeletions();
        assertThat(uploads.existsById(id)).isFalse();
        assertThat(pending.existsByUrl(url)).isFalse();
        assertThat(records.sumBytesByUserId(owner.getId())).isZero();
        assertThat(stored).isEmpty();
    }

    @Test
    void reusingAConfirmedUploadRenewsItsGraceWithoutDuplicateQueueEntries() throws Exception {
        User owner = createUser("reusedthumbnail");
        UUID id = begin(owner, "thumbnail"); stage(id, PNG, "image/png"); complete(owner, id).andExpect(status().isOk());
        String url = publicUrl(id);
        expireDeletion(url);
        complete(owner, id).andExpect(status().isOk());
        items.purgePendingImageDeletions();
        assertThat(uploads.existsById(id)).isTrue();
        assertThat(pending.count()).isEqualTo(1);
        expireDeletion(url);
        items.purgePendingImageDeletions();
        assertThat(uploads.existsById(id)).isFalse();
    }

    @Test
    void databaseDeletionFailureRetainsTheQueueAndReconcilesTheMissingObjectOnRetry() throws Exception {
        User owner = createUser("ghostretry");
        UUID id = begin(owner, "thumbnail"); stage(id, PNG, "image/png"); complete(owner, id).andExpect(status().isOk());
        String url = publicUrl(id);
        expireDeletion(url);
        jdbcTemplate.execute("CREATE TABLE upload_record_deletion_blocker (upload_id bigint REFERENCES upload_record(id))");
        try {
            jdbcTemplate.update("INSERT INTO upload_record_deletion_blocker VALUES (?)", record(id).getId());
            items.purgePendingImageDeletions();
            assertThat(stored).isEmpty();
            assertThat(pending.existsByUrl(url)).isTrue();
            assertThat(records.sumBytesByUserId(owner.getId())).isEqualTo(PNG.length);
            jdbcTemplate.update("DELETE FROM upload_record_deletion_blocker");
            items.purgePendingImageDeletions();
            assertThat(uploads.existsById(id)).isFalse();
            assertThat(pending.existsByUrl(url)).isFalse();
            assertThat(records.sumBytesByUserId(owner.getId())).isZero();
        } finally { jdbcTemplate.execute("DROP TABLE upload_record_deletion_blocker"); }
    }

    @Test
    void assignedImagesSurviveAndRemovalStartsANewGracePeriod() throws Exception {
        User owner = createUser("keptavatar");
        UUID id = begin(owner, "avatar"); stage(id, PNG, "image/png"); complete(owner, id).andExpect(status().isOk());
        String url = publicUrl(id);
        mockMvc.perform(put("/api/profile/me").header("Authorization", bearer(owner)).contentType("application/json")
                .content("{\"imageUrl\":\"" + url + "\"}")).andExpect(status().isOk());
        expireDeletion(url);
        items.purgePendingImageDeletions();
        assertThat(uploads.findById(id).orElseThrow().getState()).isEqualTo("READY");
        mockMvc.perform(put("/api/profile/me").header("Authorization", bearer(owner)).contentType("application/json")
                .content("{\"imageUrl\":\"\"}")).andExpect(status().isOk());
        items.purgePendingImageDeletions();
        assertThat(uploads.existsById(id)).isTrue();
        expireDeletion(url);
        items.purgePendingImageDeletions();
        assertThat(uploads.existsById(id)).isFalse();
    }

    @Test
    void confirmedThumbnailsInPublishedSnapshotsAreNeverReclaimed() throws Exception {
        User owner = createUser("snapshotthumbnail");
        UUID id = begin(owner, "thumbnail"); stage(id, PNG, "image/png"); complete(owner, id).andExpect(status().isOk());
        String url = publicUrl(id);
        var project = createProject(owner, "Published", "private", "Description", "");
        mockMvc.perform(put("/api/project/" + pid(project) + "/thumbnail").header("Authorization", bearer(owner))
                .contentType("application/json").content("{\"type\":\"DEDICATED\",\"imageUrl\":\"" + url + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/project/" + pid(project) + "/thumbnail").header("Authorization", bearer(owner))
                .contentType("application/json").content("{\"type\":\"NONE\"}"))
                .andExpect(status().isOk());
        expireDeletion(url);
        items.purgePendingImageDeletions();
        assertThat(uploads.findById(id).orElseThrow().getState()).isEqualTo("READY");
        assertThat(stored).containsKey(PUBLIC + ":" + record(id).getObjectKey());
    }

    @Test
    void failedConfirmedImageDeletionRemainsQueuedAndCannotBeReassignedUntilRetry() throws Exception {
        User owner = createUser("retryconfirmed");
        UUID id = begin(owner, "avatar"); stage(id, PNG, "image/png"); complete(owner, id).andExpect(status().isOk());
        String url = publicUrl(id);
        expireDeletion(url);
        doThrow(new IllegalStateException("R2 unavailable")).doAnswer(call -> delete(call.getArgument(0)))
                .when(s3Client).deleteObject(any(DeleteObjectRequest.class));
        items.purgePendingImageDeletions();
        assertThat(pending.existsByUrl(url)).isTrue();
        assertThat(uploads.findById(id).orElseThrow().getState()).isEqualTo("DELETING");
        assertThat(records.sumBytesByUserId(owner.getId())).isEqualTo(PNG.length);
        mockMvc.perform(put("/api/profile/me").header("Authorization", bearer(owner)).contentType("application/json")
                .content("{\"imageUrl\":\"" + url + "\"}")).andExpect(status().isBadRequest());
        items.purgePendingImageDeletions();
        assertThat(uploads.existsById(id)).isFalse();
        assertThat(pending.existsByUrl(url)).isFalse();
        assertThat(records.sumBytesByUserId(owner.getId())).isZero();
    }

    @Test
    void cleanupWaitsForAnAssignmentAndKeepsItsNewReference() throws Exception {
        User owner = createUser("assignwhilepurging");
        UUID id = begin(owner, "avatar"); stage(id, PNG, "image/png"); complete(owner, id).andExpect(status().isOk());
        String url = publicUrl(id);
        expireDeletion(url);
        var checked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var deleting = new java.util.concurrent.CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var assignment = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        assertThat(publicStorage.isOwnedUrl(url, "avatar", owner.getId())).isTrue();
                        checked.countDown();
                        try {
                            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                        } catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                        jdbcTemplate.update("UPDATE users SET image_url = ? WHERE id = ?", url, owner.getId());
                    }));
            assertThat(checked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(() -> {
                deleting.countDown();
                return publicStorage.deleteByPublicUrl(url, owner.getId());
            });
            assertThat(deleting.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> deletion.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assignment.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(deletion.get(10, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            assertThat(uploads.findById(id).orElseThrow().getState()).isEqualTo("READY");
            assertThat(stored).containsKey(PUBLIC + ":" + record(id).getObjectKey());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void existingLegacyProfileImagesCanRemainButNewUnconfirmedAssignmentsAreRejected() throws Exception {
        User owner = createUser("publiclegacy");
        String legacy = "https://test-bucket.example.com/avatar/" + owner.getId() + "/legacy.png";
        owner.setImageUrl(legacy); userRepository.save(owner);
        mockMvc.perform(put("/api/profile/me").header("Authorization", bearer(owner)).contentType("application/json")
                        .content("{\"bio\":\"updated\",\"imageUrl\":\"" + legacy + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/profile/me").header("Authorization", bearer(owner)).contentType("application/json")
                        .content("{\"imageUrl\":\"" + legacy.replace("legacy.png", "unconfirmed.png") + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void purgeCannotInterleaveWithAConfirmationInProgress() throws Exception {
        User owner = createUser("publicinflight");
        UUID id = begin(owner, "avatar"); stage(id, PNG, "image/png"); expire(id);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(call -> {
            started.countDown();
            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            HeadObjectRequest request = call.getArgument(0);
            Stored object = object(request.bucket(), request.key());
            return HeadObjectResponse.builder().contentLength((long) object.bytes().length).contentType(object.type()).eTag(object.etag()).build();
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var completion = executor.submit(() -> service.complete(id, owner));
            assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            service.purge();
            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
            assertThat(records.sumStorageBytesByUserId(owner.getId())).isEqualTo(PNG.length);
            release.countDown();
            completion.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(uploads.findById(id).orElseThrow().getState()).isEqualTo("READY");
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void failedConfirmationAfterPublicationCanBeCleanedWithoutLeakingObjectsOrQuota() throws Exception {
        User owner = createUser("publiccrash");
        UUID id = begin(owner, "avatar"); stage(id, PNG, "image/png");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            publish(call.getArgument(0), call.getArgument(1));
            throw new IllegalStateException("connection lost after publication");
        });
        complete(owner, id).andExpect(status().isInternalServerError());
        assertThat(uploads.findById(id).orElseThrow().getState()).isEqualTo("PENDING");
        assertThat(stored).hasSize(2);
        expire(id);
        service.purge();
        assertThat(stored).isEmpty();
        assertThat(records.sumStorageBytesByUserId(owner.getId())).isZero();
    }
}
