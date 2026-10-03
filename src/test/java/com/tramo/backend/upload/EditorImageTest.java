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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EditorImageTest extends AbstractIntegrationTest {
    @Autowired EditorImageService images;
    @Autowired ProjectPublishService publish;
    @Autowired UploadController publicUploads;
    @MockitoSpyBean EditorImageRepository imageRepository;

    @BeforeEach
    void stubStorage() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(1000L).contentType("image/jpeg").eTag("test-etag").build());
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());
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
            started.countDown();
            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Confirmation timed out");
            return HeadObjectResponse.builder().contentLength(1000L).contentType("image/jpeg").eTag("test-etag").build();
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

    private String request(Project project) {
        return """
            {"projectId":"%s","contentType":"image/jpeg","contentHash":"%s","contentBytes":1000}
            """.formatted(pid(project), "a".repeat(64));
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
