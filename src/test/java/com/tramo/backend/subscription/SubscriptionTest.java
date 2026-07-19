package com.tramo.backend.subscription;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.upload.entity.UploadRecord;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SubscriptionTest extends AbstractIntegrationTest {

    private static final String FAKE_HASH = "0123456789abcdef".repeat(4);
    private static final long FREE_STORAGE_BYTES = 524_288_000L;

    @Autowired
    private UploadRecordRepository uploadRecordRepository;

    private void upgrade(User user) throws Exception {
        mockMvc.perform(post("/api/subscription/mock-upgrade").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premium").value(true));
    }

    private void publishViaApi(User owner, Project project) throws Exception {
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isOk());
    }

    @Test
    void statusRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/subscription")).andExpect(status().isUnauthorized());
    }

    @Test
    void mockUpgradeFlipsPremiumAndIsIdempotent() throws Exception {
        User user = createUser("subscriber");

        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premium").value(false))
                .andExpect(jsonPath("$.storageQuotaBytes").value(FREE_STORAGE_BYTES))
                .andExpect(jsonPath("$.publishesPerWeek").value(5));

        upgrade(user);
        upgrade(user); // second call is a no-op, not an error

        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premium").value(true))
                .andExpect(jsonPath("$.publishesPerWeek").value(-1));
    }

    @Test
    void cancelDowngradesWithoutTouchingContent() throws Exception {
        User user = createUser("cancelling");
        upgrade(user);
        Project project = createProject(user, "Keep me", "private", "A description", null);
        publishViaApi(user, project);

        mockMvc.perform(delete("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premium").value(false));

        // content untouched by downgrade
        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk());
    }

    @Test
    void presignRejectedOverStorageQuotaButAllowedForPremium() throws Exception {
        User user = createUser("hoarder");
        UploadRecord existing = new UploadRecord();
        existing.setUserId(user.getId());
        existing.setObjectKey("editor-image/" + user.getId() + "/aa.jpg");
        existing.setBytes(FREE_STORAGE_BYTES - 500);
        existing.setCreatedDate(new Date());
        uploadRecordRepository.save(existing);

        String body = """
                {"contentType":"image/jpeg","kind":"editor-image","contentHash":"%s","contentBytes":1000}""".formatted(FAKE_HASH);

        mockMvc.perform(post("/api/uploads/presign")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());

        upgrade(user);
        mockMvc.perform(post("/api/uploads/presign")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void presignRejectsSingleFileOverMaxUploadSize() throws Exception {
        User user = createUser("bigfile");
        mockMvc.perform(post("/api/uploads/presign")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentType":"image/jpeg","kind":"editor-image","contentHash":"%s","contentBytes":27000000}""".formatted(FAKE_HASH)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sixthPublishInAWeekBlockedForFreeAllowedForPremium() throws Exception {
        User user = createUser("prolificfree");
        for (int i = 1; i <= 5; i++) {
            publishViaApi(user, createProject(user, "Path " + i, "private", "A description", null));
        }

        Project sixth = createProject(user, "Path 6", "private", "A description", null);
        mockMvc.perform(put("/api/project/" + pid(sixth))
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isTooManyRequests());

        upgrade(user);
        publishViaApi(user, sixth);
    }

    @Test
    void republishDoesNotBurnPublishQuota() throws Exception {
        User user = createUser("republisher");
        Project first = createProject(user, "First", "private", "A description", null);
        publishViaApi(user, first);
        for (int i = 2; i <= 5; i++) {
            publishViaApi(user, createProject(user, "Path " + i, "private", "A description", null));
        }

        // at the weekly limit now — but toggling an already-published-once project stays free
        mockMvc.perform(put("/api/project/" + pid(first))
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"private"}"""))
                .andExpect(status().isOk());
        publishViaApi(user, first);
    }

    @Test
    void gifAvatarIsPremiumOnlyButEditorGifsStayFree() throws Exception {
        User user = createUser("giffan");
        String gifAvatar = """
                {"contentType":"image/gif","kind":"avatar","contentHash":"%s","contentBytes":1000}""".formatted(FAKE_HASH);

        mockMvc.perform(post("/api/uploads/presign")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gifAvatar))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/api/uploads/presign")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentType":"image/gif","kind":"editor-image","contentHash":"%s","contentBytes":1000}""".formatted(FAKE_HASH)))
                .andExpect(status().isOk());

        upgrade(user);
        mockMvc.perform(post("/api/uploads/presign")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gifAvatar))
                .andExpect(status().isOk());
    }

    @Test
    void supporterBadgeAwardedOnUpgradeAndUnearnedAfterCancel() throws Exception {
        User user = createUser("badgefan");

        mockMvc.perform(get("/api/profile/stats").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.badges[?(@.code == 'supporter')].earned").value(false));

        upgrade(user);
        mockMvc.perform(get("/api/profile/stats").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.badges[?(@.code == 'supporter')].earned").value(true));

        mockMvc.perform(delete("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/profile/stats").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.badges[?(@.code == 'supporter')].earned").value(false));
    }
}
