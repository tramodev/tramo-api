package com.tramo.backend.subscription;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.subscription.entity.Plan;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.upload.entity.UploadRecord;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SubscriptionTest extends AbstractIntegrationTest {

    private static final String FAKE_HASH = "0123456789abcdef".repeat(4);
    private static final long FREE_STORAGE_BYTES = 524_288_000L;

    @Autowired
    private UploadRecordRepository uploadRecordRepository;

    @Autowired
    private SubscriptionService subscriptionService;

    private void upgrade(User user) throws Exception {
        mockMvc.perform(post("/api/subscription/mock-upgrade").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supporter").value(true));
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
    void mockUpgradeFlipsSupporterAndIsIdempotent() throws Exception {
        User user = createUser("subscriber");

        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supporter").value(false))
                .andExpect(jsonPath("$.storageQuotaBytes").value(FREE_STORAGE_BYTES))
                .andExpect(jsonPath("$.publishesPerWeek").value(-1));

        upgrade(user);
        upgrade(user); 

        mockMvc.perform(get("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supporter").value(true))
                .andExpect(jsonPath("$.publishesPerWeek").value(-1));
    }

    @Test
    void twoUsersCanShareTheSameSupporterPlan() {
        User first = createUser("patronone");
        User second = createUser("patrontwo");
        Plan plan = subscriptionService.findOrCreateSupporterPlan();

        subscriptionService.activateSupporterSubscription(first, plan);
        subscriptionService.activateSupporterSubscription(second, plan);

        assertThat(subscriptionService.isSupporter(first)).isTrue();
        assertThat(subscriptionService.isSupporter(second)).isTrue();
    }

    @Test
    void cancelDowngradesWithoutTouchingContent() throws Exception {
        User user = createUser("cancelling");
        upgrade(user);
        Project project = createProject(user, "Keep me", "private", "A description", null);
        publishViaApi(user, project);

        mockMvc.perform(delete("/api/subscription").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supporter").value(false));

        
        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk());
    }

    @Test
    void presignRejectedOverStorageQuotaButAllowedForSupporter() throws Exception {
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
    void gifAvatarIsSupporterOnlyButEditorGifsStayFree() throws Exception {
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

    @Test
    void freeUserCanPublishMoreThanFiveProjectsInARow() throws Exception {
        User user = createUser("prolificfree");
        for (int i = 1; i <= 7; i++) {
            publishViaApi(user, createProject(user, "Path " + i, "private", "A description", null));
        }
    }

    @Test
    void presignRejectsProjectIdNotOwnedByCaller() throws Exception {
        User owner = createUser("projectowner");
        User intruder = createUser("projectintruder");
        Project project = createProject(owner, "Mine", "private", "A description", null);

        String body = """
                {"contentType":"image/jpeg","kind":"editor-image","contentHash":"%s","contentBytes":1000,"projectId":"%s"}"""
                .formatted(FAKE_HASH, pid(project));

        mockMvc.perform(post("/api/uploads/presign")
                        .header("Authorization", bearer(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void presignAttributesStorageToItsOwnProjectOnly() throws Exception {
        User user = createUser("attributor");
        Project projectA = createProject(user, "Project A", "private", "A description", null);
        Project projectB = createProject(user, "Project B", "private", "A description", null);

        String body = """
                {"contentType":"image/jpeg","kind":"editor-image","contentHash":"%s","contentBytes":1000,"projectId":"%s"}"""
                .formatted(FAKE_HASH, pid(projectA));

        mockMvc.perform(post("/api/uploads/presign")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/project/" + pid(projectA)).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storageBytes").value(1000));

        mockMvc.perform(get("/api/project/" + pid(projectB)).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storageBytes").value(0));
    }
}
