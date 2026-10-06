// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.upload;

import com.jayway.jsonpath.JsonPath;
import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.service.ProjectPublishService;
import com.tramo.backend.upload.service.EditorImageService;
import com.tramo.backend.upload.repository.EditorImageRepository;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.model.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EditorImageTest extends AbstractIntegrationTest {
    private static final byte[] JPEG = jpeg();
    private static final String HASH = hash(JPEG);
    private final java.util.Map<String, byte[]> stored = new java.util.concurrent.ConcurrentHashMap<>();
    @MockitoSpyBean PrivateImageStorage privateStorage;

    static byte[] jpeg() {
        try {
            var output = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", output);
            return output.toByteArray();
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    static String hash(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    @Autowired EditorImageService images;
    @Autowired ProjectPublishService publish;
    @Autowired UploadController publicUploads;
    @MockitoSpyBean EditorImageRepository imageRepository;

    @BeforeEach
    void stubStorage() {
        stored.clear();
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(call -> {
            HeadObjectRequest request = call.getArgument(0);
            if (request.key().startsWith("images/") && !stored.containsKey(request.key())) throw NoSuchKeyException.builder().statusCode(404).build();
            return HeadObjectResponse.builder().contentLength((long) JPEG.length).contentType("image/jpeg").eTag("test-etag").build();
        });
        when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(call -> new software.amazon.awssdk.core.ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) JPEG.length).eTag("test-etag").build(), new java.io.ByteArrayInputStream(JPEG)));
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenAnswer(call -> {
            CopyObjectRequest request = call.getArgument(0);
            stored.put(request.destinationKey(), JPEG);
            return CopyObjectResponse.builder().build();
        });
    }

    @Test
    void publicEditorUploadIsBlockedBeforeQuotaAndRecording() throws Exception {
        User owner = createUser("guard");
        ReflectionTestUtils.setField(publicUploads, "publicEditorImagesEnabled", false);
        try {
            mockMvc.perform(post("/api/uploads/presign").header("Authorization", bearer(owner))
                    .contentType("application/json").content("""
                    {"kind":"editor-image","contentType":"image/jpeg","contentBytes":1000,"contentHash":"%s"}
                    """.formatted("a".repeat(64)))).andExpect(status().isForbidden());
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM upload_record", Integer.class)).isZero();
        } finally {
            ReflectionTestUtils.setField(publicUploads, "publicEditorImagesEnabled", true);
        }
    }

    @Test
    void uploadRequiresOwnerAndConfirmationIsIdempotent() throws Exception {
        User owner = createUser("uploader");
        User stranger = createUser("stranger");
        Project project = createProject(owner, "Private", "PRIVATE");
        mockMvc.perform(post("/api/uploads/editor-images/presign").header("Authorization", bearer(stranger))
                .contentType("application/json").content(request(project))).andExpect(status().isForbidden());
        UUID id = upload(project, owner);
        clearInvocations(s3Client);
        mockMvc.perform(post("/api/uploads/editor-images/" + id + "/complete").header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
        mockMvc.perform(post("/api/uploads/editor-images/" + id + "/complete").header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
    }

    @Test
    void privateReadsRequireContextAndReferencesAndUseFiveMinuteUrls() throws Exception {
        User owner = createUser("reader");
        Project project = createProject(owner, "Private", "PRIVATE");
        UUID id = upload(project, owner);
        resolve(project, owner, id, false, null, 404);
        saveImage(project, owner, id);
        String response = resolve(project, owner, id, false, null, 200);
        assertThat((String) JsonPath.read(response, "$.images[0].url")).contains("X-Amz-Expires=300").doesNotContain("r2.dev");
        resolve(project, null, id, true, null, 404);
        resolve(project, createUser("other"), id, false, null, 403);
        Project unrelated = createProject(owner, "Other", "PRIVATE");
        resolve(unrelated, owner, id, false, null, 404);
        resolve(project, owner, UUID.randomUUID(), false, null, 404);
    }

    @Test
    void publishedImagesExcludeDraftsAndHistoricalImagesRemainAvailable() throws Exception {
        User owner = createUser("publisher");
        Project project = createProject(owner, "Public", "PRIVATE", "Description", null);
        UUID published = upload(project, owner);
        long itemId = saveImage(project, owner, published);
        publish.publish(project.getId(), owner);
        Long snapshotId = jdbcTemplate.queryForObject("SELECT id FROM project_snapshot WHERE project_id = ?", Long.class, project.getId());
        UUID draft = upload(project, owner);
        updateContent(itemId, owner, content(draft), 204);
        resolve(project, null, published, true, null, 200);
        resolve(project, null, draft, true, null, 404);
        resolve(project, null, published, true, snapshotId, 200);
        jdbcTemplate.update("UPDATE project SET visibility = 'PRIVATE' WHERE id = ?", project.getId());
        resolve(project, null, published, true, snapshotId, 404);
        resolve(project, owner, published, false, snapshotId, 200);
    }

    @Test
    void contentRejectsUrlsUnconfirmedAndForeignImages() throws Exception {
        User owner = createUser("writer");
        Project project = createProject(owner, "Note", "PRIVATE");
        long item = postForId(owner, "/api/project/" + pid(project) + "/item", "{\"title\":\"Note\"}");
        updateContent(item, owner, "{\"root\":{\"children\":[{\"type\":\"image\",\"src\":\"https://example.com/private.jpg\"}]}}", 400);
        updateContent(item, owner, content(UUID.randomUUID()), 404);
        String pending = mockMvc.perform(post("/api/uploads/editor-images/presign").header("Authorization", bearer(owner))
                .contentType("application/json").content(request(project))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        updateContent(item, owner, content(UUID.fromString(JsonPath.read(pending, "$.imageId"))), 404);
        User stranger = createUser("foreign");
        UUID foreign = upload(createProject(stranger, "Other", "PRIVATE"), stranger);
        updateContent(item, owner, content(foreign), 404);
    }

    @Test
    void forkHasIndependentReferencesAndRetainsPhysicalObject() throws Exception {
        User owner = createUser("original");
        User forker = createUser("forker");
        Project source = createProject(owner, "Source", "PRIVATE", "Description", null);
        UUID original = upload(source, owner);
        saveImage(source, owner, original);
        publish.publish(source.getId(), owner);
        String forkPid = postForProjectId(forker, "/api/project/" + pid(source) + "/fork", "{}");
        Long forkId = projectIdCodec.decode(forkPid);
        UUID copy = jdbcTemplate.queryForObject("SELECT id FROM editor_image WHERE project_id = ?", UUID.class, forkId);
        assertThat(copy).isNotEqualTo(original);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM editor_image_object", Integer.class)).isEqualTo(1);
        mockMvc.perform(delete("/api/project/" + pid(source)).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE editor_image SET last_used_at = CURRENT_TIMESTAMP - INTERVAL '2 days'");
        jdbcTemplate.update("UPDATE editor_image_object SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 days'");
        images.purge();
        resolve(projectRepository.findById(forkId).orElseThrow(), forker, copy, false, null, 200);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM editor_image_object", Integer.class)).isEqualTo(1);
    }

    @Test
    void resolutionLoadsReferencesAndImagesOnceForTheWholeBatch() throws Exception {
        User owner = createUser("batch");
        Project project = createProject(owner, "Batch", "PRIVATE");
        UUID first = upload(project, owner);
        UUID second = upload(project, owner);
        saveImage(project, owner, first);
        saveImage(project, owner, second);
        long singleQueries = queryCount(() -> resolve(project, owner, first, false, null, 200));
        clearInvocations(imageRepository);
        long batchQueries = queryCount(() -> mockMvc.perform(post("/api/project/" + pid(project) + "/editor-images/resolve")
                .header("Authorization", bearer(owner)).contentType("application/json")
                .content("{\"imageIds\":[\"" + first + "\",\"" + second + "\"]}"))
                .andExpect(status().isOk()));
        assertThat(batchQueries).isLessThanOrEqualTo(singleQueries);
        verify(imageRepository, times(1)).visibleImageIds(project.getId(), null);
        verify(imageRepository, times(1)).findAll(any());
    }

    @Test
    void abandonedUploadReleasesQuotaAndDeletionFailuresCanRetry() throws Exception {
        User owner = createUser("cleanup");
        Project project = createProject(owner, "Cleanup", "PRIVATE");
        UUID id = upload(project, owner);
        jdbcTemplate.update("UPDATE editor_image SET last_used_at = CURRENT_TIMESTAMP - INTERVAL '2 days'");
        jdbcTemplate.update("UPDATE editor_image_object SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 days'");
        doThrow(new IllegalStateException("R2 unavailable")).when(s3Client).deleteObject(any(DeleteObjectRequest.class));
        images.purge();
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM editor_image_object", String.class)).isEqualTo("DELETING");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM upload_record", Integer.class)).isZero();
        doReturn(DeleteObjectResponse.builder().build()).when(s3Client).deleteObject(any(DeleteObjectRequest.class));
        images.purge();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM editor_image_object", Integer.class)).isZero();
        assertThat(imageRepository.findAll(List.of(id))).isEmpty();
    }

    @Test
    void unlistedImagesRespectBlocksAndCannotBecomePublicThumbnails() throws Exception {
        User owner = createUser("unlistedowner");
        User reader = createUser("unlistedreader");
        Project project = createProject(owner, "Unlisted", "UNLISTED");
        UUID image = upload(project, owner);
        saveImage(project, owner, image);
        resolve(project, null, image, true, null, 200);
        jdbcTemplate.update("INSERT INTO blocked_user(id, blocker_id, blocked_id) VALUES (999999, ?, ?)", owner.getId(), reader.getId());
        resolve(project, reader, image, true, null, 404);
        mockMvc.perform(put("/api/project/" + pid(project) + "/thumbnail").header("Authorization", bearer(owner))
                .contentType("application/json").content("{\"type\":\"PROJECT_IMAGE\",\"imageUrl\":\"https://test-bucket.example.com/editor-image/1/hash.jpg\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSharedItemAndItsImagesSurviveDeletingTheirOriginalProject() throws Exception {
        User owner = createUser("transcluder");
        Project original = createProject(owner, "Original", "PRIVATE");
        Project target = createProject(owner, "Target", "UNLISTED");
        UUID image = upload(original, owner);
        long item = saveImage(original, owner, image);
        long trail = postForId(owner, "/api/project/" + pid(target) + "/trail", "{\"title\":\"Shared trail\"}");
        mockMvc.perform(post("/api/trail/" + trail + "/item/" + item).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        resolve(target, null, image, true, null, 200);
        mockMvc.perform(delete("/api/project/" + pid(original)).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        resolve(target, null, image, true, null, 200);
        updateContent(item, owner, content(image), 204);
    }

    @Test
    void storageMismatchCanBeRetriedAndFinalCopyIsProtectedByEtag() throws Exception {
        User owner = createUser("mismatch");
        Project project = createProject(owner, "Mismatch", "PRIVATE");
        String response = mockMvc.perform(post("/api/uploads/editor-images/presign").header("Authorization", bearer(owner))
                .contentType("application/json").content(request(project))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(response, "$.imageId"));
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(999L).contentType("image/jpeg").eTag("wrong").build());
        mockMvc.perform(post("/api/uploads/editor-images/" + id + "/complete").header("Authorization", bearer(owner)))
                .andExpect(status().isBadRequest());
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
        stubStorage();
        mockMvc.perform(post("/api/uploads/editor-images/" + id + "/complete").header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        var copy = org.mockito.ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(copy.capture());
        assertThat(copy.getValue().copySourceIfMatch()).isEqualTo("test-etag");
        assertThat(copy.getValue().cacheControl()).isEqualTo("private, no-store");
        assertThat(copy.getValue().destinationKey()).startsWith("images/");
        assertThat(copy.getValue().sourceKey()).startsWith("temporary/");
    }

    @Test
    void malformedIdsAndOversizedBatchesAreRejected() throws Exception {
        User owner = createUser("validation");
        Project project = createProject(owner, "Validation", "PRIVATE");
        mockMvc.perform(post("/api/uploads/editor-images/not-a-uuid/complete").header("Authorization", bearer(owner)))
                .andExpect(status().isBadRequest());
        String ids = java.util.stream.IntStream.range(0, 101).mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(post("/api/project/" + pid(project) + "/editor-images/resolve").header("Authorization", bearer(owner))
                .contentType("application/json").content("{\"imageIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void purgeCannotDeleteAnUploadWhileConfirmationHoldsAnActiveLease() throws Exception {
        User owner = createUser("leasedupload");
        Project project = createProject(owner, "Lease", "PRIVATE");
        String response = mockMvc.perform(post("/api/uploads/editor-images/presign").header("Authorization", bearer(owner))
                .contentType("application/json").content(request(project))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(response, "$.imageId"));
        jdbcTemplate.update("UPDATE editor_image SET last_used_at = CURRENT_TIMESTAMP - INTERVAL '2 days'");
        jdbcTemplate.update("UPDATE editor_image_object SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 days'");
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(call -> {
            HeadObjectRequest request = call.getArgument(0);
            if (request.key().startsWith("images/")) throw NoSuchKeyException.builder().statusCode(404).build();
            started.countDown();
            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Confirmation timed out");
            return HeadObjectResponse.builder().contentLength((long) JPEG.length).contentType("image/jpeg").eTag("test-etag").build();
        });
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var completion = executor.submit(() -> images.complete(id, owner));
            assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            images.purge();
            assertThat(jdbcTemplate.queryForObject("SELECT state FROM editor_image_object", String.class)).isEqualTo("COPYING");
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM editor_image", Integer.class)).isEqualTo(1);
            release.countDown();
            completion.get(10, java.util.concurrent.TimeUnit.SECONDS);
            images.purge();
            assertThat(jdbcTemplate.queryForObject("SELECT state FROM editor_image_object", String.class)).isEqualTo("READY");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void crashAfterClaimCanRecoverOnlyAfterLeaseExpiry() throws Exception {
        User owner = createUser("crashclaim");
        Project project = createProject(owner, "Recovery", "PRIVATE");
        UUID id = pending(project, owner);
        doThrow(new AssertionError("process stopped")).when(privateStorage).validate(any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(id, owner)).isInstanceOf(AssertionError.class);
        doCallRealMethod().when(privateStorage).validate(any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(id, owner)).isInstanceOf(IllegalArgumentException.class);
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
        expireLease();
        images.complete(id, owner);
        assertState("READY");
        assertThat(jdbcTemplate.queryForObject("SELECT validated_hash FROM editor_image_object", String.class)).isEqualTo(HASH);
    }

    @Test
    void crashAfterCopyReconcilesFinalObjectWithoutCopyingAgain() throws Exception {
        User owner = createUser("crashcopy");
        UUID id = pending(createProject(owner, "Recovery", "PRIVATE"), owner);
        doThrow(new AssertionError("process stopped")).when(imageRepository).finishAttempt(any(), any(), eq("READY"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(id, owner)).isInstanceOf(AssertionError.class);
        assertState("COPYING");
        assertThat(stored).hasSize(1);
        expireLease();
        doCallRealMethod().when(imageRepository).finishAttempt(any(), any(), eq("READY"));
        clearInvocations(s3Client);
        images.complete(id, owner);
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
        assertState("READY");
    }

    @Test
    void expiredAttemptCannotFinishOrResetRecovery() throws Exception {
        User owner = createUser("staleattempt");
        UUID id = pending(createProject(owner, "Recovery", "PRIVATE"), owner);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var recoveryStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseRecovery = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var result = call.callRealMethod();
            if (calls.getAndIncrement() == 0) {
                started.countDown();
                if (!release.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            } else {
                recoveryStarted.countDown();
                if (!releaseRecovery.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            }
            return result;
        }).when(privateStorage).validate(any());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var old = executor.submit(() -> images.complete(id, owner));
            assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            UUID oldAttempt = jdbcTemplate.queryForObject("SELECT attempt_id FROM editor_image_object", UUID.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(id, owner)).isInstanceOf(IllegalArgumentException.class);
            expireLease();
            var recovery = executor.submit(() -> images.complete(id, owner));
            assertThat(recoveryStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            UUID newAttempt = jdbcTemplate.queryForObject("SELECT attempt_id FROM editor_image_object", UUID.class);
            assertThat(newAttempt).isNotEqualTo(oldAttempt);
            release.countDown();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> old.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalArgumentException.class);
            assertState("COPYING");
            assertThat(jdbcTemplate.queryForObject("SELECT attempt_id FROM editor_image_object", UUID.class)).isEqualTo(newAttempt);
            releaseRecovery.countDown();
            recovery.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertState("READY");
            verify(s3Client, times(1)).copyObject(any(CopyObjectRequest.class));
        } finally { release.countDown(); releaseRecovery.countDown(); executor.shutdownNow(); }
    }

    @Test
    void purgeCanWinAgainstExpiredValidationWithoutLeavingFinalObject() throws Exception {
        User owner = createUser("purgerace");
        UUID id = pending(createProject(owner, "Race", "PRIVATE"), owner);
        doAnswer(call -> {
            var validated = call.callRealMethod();
            expireLease();
            jdbcTemplate.update("UPDATE editor_image SET last_used_at = CURRENT_TIMESTAMP - INTERVAL '2 days'");
            images.purge();
            return validated;
        }).when(privateStorage).validate(any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(id, owner)).isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM editor_image_object", Integer.class)).isZero();
        assertThat(stored).isEmpty();
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void purgeCannotInterleaveCopyAndReadyEvenAfterLeaseExpires() throws Exception {
        User owner = createUser("copyrace");
        UUID id = pending(createProject(owner, "Race", "PRIVATE"), owner);
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid " +
                    "WHERE l.locktype = 'advisory' AND l.granted AND a.xact_start IS NOT NULL", Integer.class)).isZero();
            expireLease();
            jdbcTemplate.update("UPDATE editor_image SET last_used_at = CURRENT_TIMESTAMP - INTERVAL '2 days'");
            images.purge();
            assertState("COPYING");
            return call.callRealMethod();
        }).when(privateStorage).confirm(any(), any());
        images.complete(id, owner);
        assertState("READY");
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void falseBytesAndWrongHashCannotBeAttached() throws Exception {
        User owner = createUser("falsebytes");
        Project project = createProject(owner, "Invalid", "PRIVATE");
        UUID id = pending(project, owner);
        byte[] fake = new byte[JPEG.length];
        when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(call -> new software.amazon.awssdk.core.ResponseInputStream<>(
                GetObjectResponse.builder().build(), new java.io.ByteArrayInputStream(fake)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(id, owner)).hasMessageContaining("SHA-256");
        assertState("PENDING");
        jdbcTemplate.update("UPDATE editor_image_object SET content_hash = ?", hash(fake));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(id, owner)).hasMessageContaining("image");
        assertState("PENDING");
        long item = postForId(owner, "/api/project/" + pid(project) + "/item", "{\"title\":\"Note\"}");
        updateContent(item, owner, content(id), 404);
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void replacementDuringValidationFailsTheConditionalCopy() throws Exception {
        User owner = createUser("replacement");
        UUID id = pending(createProject(owner, "Replace", "PRIVATE"), owner);
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(412).message("etag changed").build());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(id, owner)).isInstanceOf(S3Exception.class);
        assertState("PENDING");
        assertThat(stored).isEmpty();
        var read = org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(read.capture());
        assertThat(read.getValue().ifMatch()).isEqualTo("test-etag");
        var copy = org.mockito.ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(copy.capture());
        assertThat(copy.getValue().copySourceIfMatch()).isEqualTo("test-etag");
        assertThat(copy.getValue().overrideConfiguration().orElseThrow().headers())
                .containsEntry("cf-copy-destination-if-none-match", List.of("*"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"jpeg", "png", "gif", "webp"})
    void realFormatsCanBecomeReady(String format) throws Exception {
        byte[] bytes;
        if (format.equals("webp")) {
            try (var input = getClass().getResourceAsStream("/images/valid.webp")) { bytes = input.readAllBytes(); }
        } else {
            var output = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), format, output);
            bytes = output.toByteArray();
        }
        String type = "image/" + format;
        User owner = createUser("format" + format);
        Project project = createProject(owner, "Valid format", "PRIVATE");
        String request = "{\"projectId\":\"%s\",\"contentType\":\"%s\",\"contentBytes\":%d,\"contentHash\":\"%s\"}"
                .formatted(pid(project), type, bytes.length, hash(bytes));
        String response = mockMvc.perform(post("/api/uploads/editor-images/presign").header("Authorization", bearer(owner))
                .contentType("application/json").content(request)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(response, "$.imageId"));
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(call -> {
            HeadObjectRequest head = call.getArgument(0);
            if (head.key().startsWith("images/")) throw NoSuchKeyException.builder().statusCode(404).build();
            return HeadObjectResponse.builder().contentLength((long) bytes.length).contentType(type).eTag("format-etag").build();
        });
        when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(call -> new software.amazon.awssdk.core.ResponseInputStream<>(
                GetObjectResponse.builder().build(), new java.io.ByteArrayInputStream(bytes)));
        mockMvc.perform(post("/api/uploads/editor-images/" + id + "/complete").header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        assertState("READY");
        assertThat(jdbcTemplate.queryForObject("SELECT validated_hash FROM editor_image_object", String.class)).isEqualTo(hash(bytes));
    }

    @Test
    void temporaryReplacementBeforeDownloadAndOverlongBodyAreRejected() throws Exception {
        User owner = createUser("readreplacement");
        UUID id = pending(createProject(owner, "Replace", "PRIVATE"), owner);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(412).build());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(id, owner)).isInstanceOf(S3Exception.class);
        assertState("PENDING");
        byte[] oversized = java.util.Arrays.copyOf(JPEG, JPEG.length + 1);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(call -> new software.amazon.awssdk.core.ResponseInputStream<>(
                GetObjectResponse.builder().build(), new java.io.ByteArrayInputStream(oversized)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(id, owner)).hasMessageContaining("size");
        assertState("PENDING");
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void recoveryUsesPersistedHashForUploadsCreatedBeforeMigration() throws Exception {
        User owner = createUser("legacyrecovery");
        UUID id = pending(createProject(owner, "Recovery", "PRIVATE"), owner);
        jdbcTemplate.update("UPDATE editor_image_object SET content_hash = NULL");
        doThrow(new AssertionError("process stopped")).when(imageRepository).finishAttempt(any(), any(), eq("READY"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(id, owner)).isInstanceOf(AssertionError.class);
        assertThat(jdbcTemplate.queryForObject("SELECT validated_hash FROM editor_image_object", String.class)).isEqualTo(HASH);
        expireLease();
        doCallRealMethod().when(imageRepository).finishAttempt(any(), any(), eq("READY"));
        byte[] wrong = new byte[JPEG.length];
        when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(call -> new software.amazon.awssdk.core.ResponseInputStream<>(
                GetObjectResponse.builder().build(), new java.io.ByteArrayInputStream(wrong)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(id, owner)).hasMessageContaining("SHA-256");
        assertState("PENDING");
        verify(s3Client, times(1)).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void copyWithUnknownOutcomeCanBeReconciledAfterFailure() throws Exception {
        User owner = createUser("unknowncopy");
        UUID id = pending(createProject(owner, "Recovery", "PRIVATE"), owner);
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenAnswer(call -> {
            CopyObjectRequest copy = call.getArgument(0);
            stored.put(copy.destinationKey(), JPEG);
            throw new IllegalStateException("response lost after copy");
        });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(id, owner)).hasMessageContaining("response lost");
        assertState("PENDING");
        images.complete(id, owner);
        assertState("READY");
        verify(s3Client, times(1)).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void validationConcurrencyIsBoundedBeforeDownloadingBytes() throws Exception {
        User owner = createUser("boundeddecode");
        Project project = createProject(owner, "Bounds", "PRIVATE");
        UUID first = pending(project, owner), second = pending(project, owner), third = pending(project, owner);
        var started = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(call -> {
            started.countDown();
            if (!release.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            return new software.amazon.awssdk.core.ResponseInputStream<>(GetObjectResponse.builder().build(), new java.io.ByteArrayInputStream(JPEG));
        });
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> images.complete(first, owner));
            var two = executor.submit(() -> images.complete(second, owner));
            assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(third, owner)).hasMessageContaining("busy");
            verify(s3Client, times(2)).getObject(any(GetObjectRequest.class));
            release.countDown();
            one.get(10, java.util.concurrent.TimeUnit.SECONDS);
            two.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(imageRepository.findAll(List.of(third)).get(0).state()).isEqualTo("PENDING");
            images.complete(third, owner);
            assertThat(imageRepository.findAll(List.of(third)).get(0).state()).isEqualTo("READY");
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void recoveryReloadsHashPersistedAfterInitialLookup() throws Exception {
        User owner = createUser("hashrace");
        UUID id = pending(createProject(owner, "Recovery", "PRIVATE"), owner);
        jdbcTemplate.update("UPDATE editor_image_object SET content_hash = NULL");
        doAnswer(call -> {
            jdbcTemplate.update("UPDATE editor_image_object SET validated_hash = ?", HASH);
            return call.callRealMethod();
        }).when(imageRepository).claimCompletion(any());
        byte[] replacement = new byte[JPEG.length];
        when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(call -> new software.amazon.awssdk.core.ResponseInputStream<>(
                GetObjectResponse.builder().build(), new java.io.ByteArrayInputStream(replacement)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> images.complete(id, owner)).hasMessageContaining("SHA-256");
        assertState("PENDING");
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    private UUID pending(Project project, User owner) throws Exception {
        String response = mockMvc.perform(post("/api/uploads/editor-images/presign").header("Authorization", bearer(owner))
                .contentType("application/json").content(request(project))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.imageId"));
    }

    private void expireLease() {
        jdbcTemplate.update("UPDATE editor_image_object SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
    }

    private void assertState(String state) {
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM editor_image_object", String.class)).isEqualTo(state);
    }

    private String request(Project project) {
        return """
            {"projectId":"%s","contentType":"image/jpeg","contentHash":"%s","contentBytes":%d}
            """.formatted(pid(project), HASH, JPEG.length);
    }

    private UUID upload(Project project, User owner) throws Exception {
        String response = mockMvc.perform(post("/api/uploads/editor-images/presign").header("Authorization", bearer(owner))
                .contentType("application/json").content(request(project))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(response, "$.uploadUrl")).contains("test-private-bucket");
        UUID id = UUID.fromString(JsonPath.read(response, "$.imageId"));
        mockMvc.perform(post("/api/uploads/editor-images/" + id + "/complete").header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        return id;
    }

    private long saveImage(Project project, User owner, UUID image) throws Exception {
        long item = postForId(owner, "/api/project/" + pid(project) + "/item", "{\"title\":\"Note\"}");
        updateContent(item, owner, content(image), 204);
        return item;
    }

    private String content(UUID image) {
        return "{\"root\":{\"children\":[{\"type\":\"image\",\"version\":2,\"imageId\":\"" + image + "\"}]}}";
    }

    private void updateContent(long item, User owner, String content, int status) throws Exception {
        mockMvc.perform(put("/api/item/" + item + "/content").header("Authorization", bearer(owner))
                .contentType("application/json").content(new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("content", content))))
                .andExpect(status().is(status));
    }

    private String resolve(Project project, User user, UUID image, boolean publicRead, Long snapshot, int expected) throws Exception {
        var request = post("/api/" + (publicRead ? "public/" : "") + "project/" + pid(project) + "/editor-images/resolve")
                .contentType("application/json").content("{\"imageIds\":[\"" + image + "\"]" + (snapshot == null ? "" : ",\"snapshotId\":" + snapshot) + "}");
        if (user != null) request.header("Authorization", bearer(user));
        return mockMvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
}
