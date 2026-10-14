package com.tramo.backend.subscription;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.service.ProjectPublishService;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.upload.entity.UploadRecord;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = {"app.limits.free.storage-bytes=8192", "app.limits.supporter.storage-bytes=16384"})
class StorageQuotaTest extends AbstractIntegrationTest {
    @Autowired UploadRecordRepository uploads;
    @Autowired SubscriptionService subscriptions;
    @Autowired ProjectPublishService publish;

    private long note(User user, Project project) throws Exception {
        return postForId(user, "/api/project/" + pid(project) + "/item", "{\"title\":\"Note\"}");
    }

    private org.springframework.test.web.servlet.ResultActions save(User user, long id, String content) throws Exception {
        return mockMvc.perform(put("/api/item/" + id + "/content").header("Authorization", bearer(user))
                .contentType("application/json").content("{\"content\":\"" + content + "\"}"));
    }

    private void reserve(User user, long bytes) {
        UploadRecord record = new UploadRecord();
        record.setUserId(user.getId());
        record.setObjectKey("thumbnail/" + user.getId() + "/reserved.jpg");
        record.setBytes(bytes);
        uploads.save(record);
    }

    @Test
    void notesUseUtf8BytesAndShareQuotaWithUploads() throws Exception {
        User user = createUser("textquota");
        long note = note(user, createProject(user, "Quota", "private"));
        reserve(user, 8192 - 10);
        save(user, note, "é".repeat(6)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("STORAGE_QUOTA_EXCEEDED"));
        save(user, note, "é".repeat(5)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.storageUsedBytes").value(8192))
                .andExpect(jsonPath("$.storageImageBytes").value(8182))
                .andExpect(jsonPath("$.storageTextBytes").value(10));
        save(user, note, "a").andExpect(status().isNoContent());
    }

    @Test
    void overQuotaAccountsCanStillReduceTheirNotes() throws Exception {
        User user = createUser("reducequota");
        long note = note(user, createProject(user, "Reduce", "private"));
        save(user, note, "previous content").andExpect(status().isNoContent());
        reserve(user, 8192);
        save(user, note, "short").andExpect(status().isNoContent());
        save(user, note, "more content").andExpect(status().isTooManyRequests());
    }

    @Test
    void transclusionIsCountedOnceAndSnapshotsConsumeTheirStoredBytes() throws Exception {
        User user = createUser("countquota");
        Project project = createProject(user, "Count", "private", "Description", null);
        long note = note(user, project);
        save(user, note, "é").andExpect(status().isNoContent());
        for (int i = 0; i < 2; i++) {
            long trail = postForId(user, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Trail\"}");
            mockMvc.perform(post("/api/trail/" + trail + "/item/" + note).header("Authorization", bearer(user)))
                    .andExpect(status().isNoContent());
        }
        assertThat(uploads.sumStorageBytesByUserId(user.getId())).isEqualTo(2);
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(user)))
                .andExpect(status().isOk());
        Long snapshotBytes = jdbcTemplate.queryForObject("SELECT SUM(octet_length(content)) FROM project_snapshot", Long.class);
        assertThat(uploads.sumStorageBytesByUserId(user.getId())).isEqualTo(2 + snapshotBytes);
        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storageImageBytes").value(0))
                .andExpect(jsonPath("$.storageTextBytes").value(2 + snapshotBytes));
    }

    @Test
    void publishAndVisibilityUpdatesCannotBypassQuotaOrLeavePartialSnapshots() throws Exception {
        User user = createUser("publishquota");
        Project project = createProject(user, "Publish", "private", "Description", null);
        save(user, note(user, project), "note").andExpect(status().isNoContent());
        reserve(user, 8192 - 5);
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(user)))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(put("/api/project/" + pid(project)).header("Authorization", bearer(user))
                        .contentType("application/json").content("{\"visibility\":\"published\"}"))
                .andExpect(status().isTooManyRequests());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM project_snapshot", Integer.class)).isZero();
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getFirstPublishedDate()).isNull();
    }

    @Test
    void forksCannotBypassTextQuota() throws Exception {
        User owner = createUser("forkquotaowner");
        User forker = createUser("forkquotareader");
        Project project = createProject(owner, "Source", "unlisted", "Description", null);
        save(owner, note(owner, project), "source text").andExpect(status().isNoContent());
        reserve(forker, 8192 - 1);
        mockMvc.perform(post("/api/project/" + pid(project) + "/fork").header("Authorization", bearer(forker)))
                .andExpect(status().isTooManyRequests());
        assertThat(projectRepository.findByOwnerId(forker.getId())).isEmpty();
    }

    @Test
    void concurrentWritesCannotSpendTheSameRemainingQuota() throws Exception {
        User user = createUser("concurrentquota");
        Project project = createProject(user, "Concurrent", "private");
        long first = note(user, project), second = note(user, project);
        reserve(user, 8192 - 10);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> { start.await(); return save(user, first, "1234567").andReturn().getResponse().getStatus(); });
            var b = executor.submit(() -> { start.await(); return save(user, second, "1234567").andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(List.of(a.get(10, java.util.concurrent.TimeUnit.SECONDS), b.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(204, 429);
            assertThat(uploads.sumStorageBytesByUserId(user.getId())).isEqualTo(8189);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void oversizedNoteIsRejectedBeforeParsingAndWithoutChangingSavedContent() throws Exception {
        User user = createUser("largenote");
        long note = note(user, createProject(user, "Large", "private"));
        save(user, note, "before").andExpect(status().isNoContent());
        save(user, note, "é".repeat(524289)).andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        mockMvc.perform(get("/api/item/" + note + "/content").header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("before"));
    }

    @Test
    void userControlledPaginationIsBounded() throws Exception {
        User user = createUser("paginationlimits");
        for (String path : List.of("/api/notifications", "/api/public/explore", "/api/profile/published", "/api/profile/activity")) {
            mockMvc.perform(get(path).header("Authorization", bearer(user)).param("size", "101"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(path).header("Authorization", bearer(user)).param("page", "2147483647"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void publicUploadsUseTheSameQuotaAsNotes() throws Exception {
        User user = createUser("uploadtextquota");
        Project project = createProject(user, "Upload", "private");
        save(user, note(user, project), "a".repeat(8190)).andExpect(status().isNoContent());
        for (int bytes : new int[]{3, 2}) {
            mockMvc.perform(post("/api/uploads/presign").header("Authorization", bearer(user))
                            .contentType("application/json").content("""
                                    {"kind":"thumbnail","contentType":"image/jpeg","contentHash":"%s","contentBytes":%d}
                                    """.formatted("a".repeat(64), bytes)))
                    .andExpect(status().is(bytes == 3 ? 429 : 200));
        }
        assertThat(uploads.sumStorageBytesByUserId(user.getId())).isEqualTo(8192);
    }

    @Test
    void supporterUsesTheExistingHigherPlanQuota() throws Exception {
        User user = createUser("supportertextquota");
        long note = note(user, createProject(user, "Supporter", "private"));
        reserve(user, 8192);
        save(user, note, "text").andExpect(status().isTooManyRequests());
        subscriptions.activateSupporterSubscription(user, subscriptions.findOrCreateSupporterPlan());
        save(user, note, "text").andExpect(status().isNoContent());
    }

    @Test
    void historicalBackfillDoesNotPreventStartupWhenAnAccountIsOverQuota() {
        User user = createUser("backfillquota");
        createProject(user, "Legacy", "published", "Description", null);
        reserve(user, 8192);
        publish.backfillMissingPublishSnapshots();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM project_snapshot", Integer.class)).isZero();
    }

    @Test
    void trailDescriptionsUseUtf8BytesAndShareTheQuotaWithNotesAndUploads() throws Exception {
        User user = createUser("trailquota");
        Project project = createProject(user, "Trail quota", "private");
        reserve(user, 8192 - 10);
        mockMvc.perform(post("/api/project/" + pid(project) + "/trail").header("Authorization", bearer(user))
                        .contentType("application/json").content("{\"title\":\"Trail\",\"description\":\"" + "é".repeat(6) + "\"}"))
                .andExpect(status().isTooManyRequests());
        long trail = postForId(user, "/api/project/" + pid(project) + "/trail",
                "{\"title\":\"Trail\",\"description\":\"" + "é".repeat(5) + "\"}");
        assertThat(uploads.sumStorageBytesByUserId(user.getId())).isEqualTo(8192);
        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storageImageBytes").value(8182))
                .andExpect(jsonPath("$.storageTextBytes").value(10));
        save(user, note(user, project), "a").andExpect(status().isTooManyRequests());
        mockMvc.perform(put("/api/trail/" + trail).header("Authorization", bearer(user))
                        .contentType("application/json").content("{\"description\":\"a\"}"))
                .andExpect(status().isOk());
        assertThat(uploads.sumStorageBytesByUserId(user.getId())).isEqualTo(8183);
        mockMvc.perform(delete("/api/trail/" + trail).header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());
        assertThat(uploads.sumStorageBytesByUserId(user.getId())).isEqualTo(8182);
    }

    @Test
    void oversizedTrailDescriptionsAreRejectedOnCreateAndUpdateWithoutChangingData() throws Exception {
        User user = createUser("traildescriptionlimit");
        Project project = createProject(user, "Trail limit", "private");
        String oversized = "a".repeat(10001);
        mockMvc.perform(post("/api/project/" + pid(project) + "/trail").header("Authorization", bearer(user))
                        .contentType("application/json").content("{\"title\":\"Trail\",\"description\":\"" + oversized + "\"}"))
                .andExpect(status().isBadRequest());
        long trail = postForId(user, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Trail\",\"description\":\"before\"}");
        mockMvc.perform(put("/api/trail/" + trail).header("Authorization", bearer(user))
                        .contentType("application/json").content("{\"description\":\"" + oversized + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/trail/" + trail).header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("before"));
    }

    @Test
    void historicalOverQuotaTrailDescriptionsCanBeReducedButNotIncreased() throws Exception {
        User user = createUser("historicaltrailquota");
        Project project = createProject(user, "Legacy trail", "private");
        long trail = postForId(user, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Trail\"}");
        jdbcTemplate.update("UPDATE trail SET description = ? WHERE id = ?", "a".repeat(11000), trail);
        assertThat(uploads.sumStorageBytesByUserId(user.getId())).isEqualTo(11000);
        mockMvc.perform(put("/api/trail/" + trail).header("Authorization", bearer(user))
                        .contentType("application/json").content("{\"description\":\"before\"}"))
                .andExpect(status().isOk());
        reserve(user, 8192);
        mockMvc.perform(put("/api/trail/" + trail).header("Authorization", bearer(user))
                        .contentType("application/json").content("{\"description\":\"short\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/trail/" + trail).header("Authorization", bearer(user))
                        .contentType("application/json").content("{\"description\":\"longer\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void concurrentTrailCreatesCannotSpendTheSameRemainingQuota() throws Exception {
        User user = createUser("concurrenttrailquota");
        Project project = createProject(user, "Concurrent trails", "private");
        reserve(user, 8192 - 10);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Integer> create = () -> {
            start.await();
            return mockMvc.perform(post("/api/project/" + pid(project) + "/trail").header("Authorization", bearer(user))
                            .contentType("application/json").content("{\"title\":\"Trail\",\"description\":\"1234567\"}"))
                    .andReturn().getResponse().getStatus();
        };
        try {
            var first = executor.submit(create);
            var second = executor.submit(create);
            start.countDown();
            assertThat(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 429);
            assertThat(uploads.sumStorageBytesByUserId(user.getId())).isEqualTo(8189);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void forkedTrailDescriptionsCannotBypassQuota() throws Exception {
        User owner = createUser("trailforkowner");
        User forker = createUser("trailforkreader");
        Project project = createProject(owner, "Trail fork", "unlisted");
        postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Trail\",\"description\":\"description\"}");
        reserve(forker, 8192 - 1);
        mockMvc.perform(post("/api/project/" + pid(project) + "/fork").header("Authorization", bearer(forker)))
                .andExpect(status().isTooManyRequests());
        assertThat(projectRepository.findByOwnerId(forker.getId())).isEmpty();
    }

    @Test
    void storageBreakdownIsEmptyForANewAccountAndDoesNotIncludeOtherUsers() throws Exception {
        User user = createUser("emptybreakdown");
        User other = createUser("otherbreakdown");
        Project project = createProject(other, "Other storage", "private");
        reserve(other, 100);
        save(other, note(other, project), "text").andExpect(status().isNoContent());
        postForId(other, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Trail\",\"description\":\"description\"}");
        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storageUsedBytes").value(0))
                .andExpect(jsonPath("$.storageImageBytes").value(0))
                .andExpect(jsonPath("$.storageTextBytes").value(0));
    }
}
