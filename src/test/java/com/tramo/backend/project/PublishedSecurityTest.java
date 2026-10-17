package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectThumbnailType;
import com.tramo.backend.project.service.ProjectPublishService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = {"app.snapshot.max-bytes=8192", "app.snapshot.max-trails=2",
        "app.snapshot.max-items=3", "app.snapshot.max-references=3"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublishedSecurityTest extends AbstractIntegrationTest {
    @Autowired ProjectPublishService publishService;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    private long trail(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"" + title + "\"}");
    }

    private void publish(User owner, Project project) {
        publishService.publish(project.getId(), owner);
    }

    @Test
    void publicThumbnailDoesNotExposeDraftAndRepublishUpdatesIt() throws Exception {
        User owner = createUser("graphsecurity");
        Project project = createProject(owner, "Public", "private", "Description", null);
        long trailId = trail(owner, project, "Published trail");
        postForId(owner, "/api/trail/" + trailId + "/item", "{\"title\":\"Published note\"}");
        project.setThumbnailType(ProjectThumbnailType.GRAPH);
        projectRepository.save(project);
        publish(owner, project);
        jdbcTemplate.update("update trail set title = 'SECRET DRAFT' where id = ?", trailId);
        postForId(owner, "/api/trail/" + trailId + "/item", "{\"title\":\"SECRET NOTE\"}");

        String feed = mockMvc.perform(get("/api/public/explore")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(feed).contains("Published trail").doesNotContain("SECRET DRAFT", "SECRET NOTE");
        assertThat(mockMvc.perform(get("/api/public/users/graphsecurity/published")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).doesNotContain("SECRET DRAFT", "SECRET NOTE");
        assertThat(mockMvc.perform(get("/api/project/" + pid(project)).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("SECRET DRAFT", "SECRET NOTE");
        publish(owner, project);
        assertThat(mockMvc.perform(get("/api/public/explore")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).contains("SECRET DRAFT", "SECRET NOTE");
    }

    @Test
    void forkAttributionNeverReadsPrivateSourceTitle() throws Exception {
        User owner = createUser("sourceowner");
        User forker = createUser("forkowner");
        Project source = createProject(owner, "Public source", "private", "Description", null);
        publish(owner, source);
        String forkId = postForProjectId(forker, "/api/project/" + pid(source) + "/fork", "{}");
        jdbcTemplate.update("update project set title = 'PRIVATE SOURCE TITLE', visibility = 'private' where id = ?", source.getId());
        mockMvc.perform(get("/api/project/" + forkId).header("Authorization", bearer(forker)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.forkedFromTitle").value("Public source"));
        mockMvc.perform(post("/api/project/" + forkId + "/publish").header("Authorization", bearer(forker)))
                .andExpect(status().isOk());
        String body = mockMvc.perform(get("/api/public/project/" + forkId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("Public source").doesNotContain("PRIVATE SOURCE TITLE");
    }

    @Test
    void publishRejectsTooManyTrailsAndRollsBack() throws Exception {
        User owner = createUser("limitowner");
        Project project = createProject(owner, "Limited", "private", "Description", null);
        trail(owner, project, "One");
        trail(owner, project, "Two");
        trail(owner, project, "Three");
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isPayloadTooLarge());
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getVisibility().toJson()).isEqualTo("private");
        assertThat(jdbcTemplate.queryForObject("select count(*) from project_snapshot", Long.class)).isZero();
    }

    @Test
    void imageThumbnailUsesPublishedUrlUntilRepublish() throws Exception {
        User owner = createUser("imagesecurity");
        Project project = createProject(owner, "Image", "private", "Description", null);
        project.setThumbnailType(ProjectThumbnailType.DEDICATED);
        project.setThumbnailImageUrl("https://example.com/public.png");
        projectRepository.save(project);
        publish(owner, project);
        jdbcTemplate.update("update project set thumbnail_image_url = 'https://example.com/SECRET.png' where id = ?", project.getId());
        for (String path : java.util.List.of("/api/public/explore", "/api/public/users/imagesecurity/published",
                "/api/public/project/" + pid(project))) {
            assertThat(mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                    .contains("public.png").doesNotContain("SECRET.png");
        }
        publish(owner, project);
        mockMvc.perform(get("/api/public/project/" + pid(project))).andExpect(status().isOk())
                .andExpect(jsonPath("$.thumbnailImageUrl").value("https://example.com/SECRET.png"));
    }

    @Test
    void automaticGraphDoesNotExposeNewConnections() throws Exception {
        User owner = createUser("fallbacksecurity");
        Project project = createProject(owner, "Automatic", "private", "Description", null);
        long trailId = trail(owner, project, "Trail");
        long first = postForId(owner, "/api/trail/" + trailId + "/item", "{\"title\":\"First\"}");
        long second = postForId(owner, "/api/trail/" + trailId + "/item", "{\"title\":\"Second\"}");
        publish(owner, project);
        String content = "{\"root\":{\"children\":[{\"type\":\"link\",\"rel\":\"tramo-idea:" + second + "\"}]}}";
        Long contentId = jdbcTemplate.queryForObject("select nextval('item_content_seq')", Long.class);
        jdbcTemplate.update("insert into item_content (id, content, extraction_epoch) values (?, ?, 0)", contentId, content);
        jdbcTemplate.update("update item set content_id = ? where id = ?", contentId, first);
        mockMvc.perform(get("/api/public/users/fallbacksecurity/published")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].thumbnailGraph").isEmpty());
        publish(owner, project);
        assertThat(mockMvc.perform(get("/api/public/explore")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).contains("linkedItemIds\":[\"" + second + "\"]");
    }

    @Test
    void repeatedMembershipsCountTowardReferenceAndByteLimits() throws Exception {
        User owner = createUser("referencesecurity");
        Project project = createProject(owner, "Repeated", "private", "Description", null);
        long one = trail(owner, project, "One");
        long two = trail(owner, project, "Two");
        long item = postForId(owner, "/api/trail/" + one + "/item", "{\"title\":\"Repeated note\"}");
        jdbcTemplate.update("insert into trail_item (id, trail_id, item_id, order_index) values (nextval('trail_item_seq'), ?, ?, 0)", two, item);
        publish(owner, project);
        jdbcTemplate.update("insert into trail_item (id, trail_id, item_id, order_index) values (nextval('trail_item_seq'), ?, ?, 1)", two, item);
        publish(owner, project);
        jdbcTemplate.update("insert into trail_item (id, trail_id, item_id, order_index) values (nextval('trail_item_seq'), ?, ?, 2)", two, item);
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isPayloadTooLarge());
        assertThat(jdbcTemplate.queryForObject("select count(*) from project_snapshot", Long.class)).isEqualTo(2);
    }

    @Test
    void oversizedLegacySnapshotCannotBeReadOrForked() throws Exception {
        User owner = createUser("largesecurity");
        User other = createUser("largereader");
        Project project = createProject(owner, "Legacy", "private", "Description", null);
        publish(owner, project);
        Long version = jdbcTemplate.queryForObject("select id from project_snapshot", Long.class);
        String original = jdbcTemplate.queryForObject("select content from project_snapshot", String.class);
        jdbcTemplate.update("update project_snapshot set content = ?", original + " ".repeat(8192 - original.length()));
        mockMvc.perform(get("/api/public/project/" + pid(project))).andExpect(status().isOk());
        jdbcTemplate.update("update project_snapshot set content = content || ' '");
        for (String path : java.util.List.of("/api/public/project/" + pid(project),
                "/api/public/project/" + pid(project) + "/versions/" + version,
                "/api/project/" + pid(project) + "/versions/" + version)) {
            mockMvc.perform(get(path).header("Authorization", bearer(owner))).andExpect(status().isPayloadTooLarge());
        }
        mockMvc.perform(post("/api/project/" + pid(project) + "/fork").header("Authorization", bearer(other)))
                .andExpect(status().isPayloadTooLarge());
        assertThat(projectRepository.count()).isEqualTo(1);
    }

    @Test
    void repeatedNoteContentIsBudgetedForEverySerializedOccurrence() throws Exception {
        User owner = createUser("repeatedbytes");
        Project project = createProject(owner, "Bytes", "private", "Description", null);
        long one = trail(owner, project, "One");
        long two = trail(owner, project, "Two");
        long item = postForId(owner, "/api/trail/" + one + "/item", "{\"title\":\"Note\"}");
        Long content = jdbcTemplate.queryForObject("select nextval('item_content_seq')", Long.class);
        jdbcTemplate.update("insert into item_content (id, content, extraction_epoch) values (?, ?, 0)", content, "x".repeat(500));
        jdbcTemplate.update("update item set content_id = ? where id = ?", content, item);
        jdbcTemplate.update("insert into trail_item (id, trail_id, item_id, order_index) values (nextval('trail_item_seq'), ?, ?, 0)", two, item);
        publish(owner, project);
        jdbcTemplate.update("insert into trail_item (id, trail_id, item_id, order_index) values (nextval('trail_item_seq'), ?, ?, 1)", two, item);
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isPayloadTooLarge());
        assertThat(jdbcTemplate.queryForObject("select count(*) from project_snapshot", Long.class)).isEqualTo(1);
    }

    @Test
    void exactTrailAndNoteLimitsAreAcceptedAndNextNoteIsRejected() throws Exception {
        User owner = createUser("exactlimits");
        Project project = createProject(owner, "Exact", "private", "Description", null);
        long one = trail(owner, project, "One");
        long two = trail(owner, project, "Two");
        postForId(owner, "/api/trail/" + one + "/item", "{\"title\":\"One\"}");
        postForId(owner, "/api/trail/" + two + "/item", "{\"title\":\"Two\"}");
        postForId(owner, "/api/trail/" + two + "/item", "{\"title\":\"Three\"}");
        publish(owner, project);
        postForId(owner, "/api/trail/" + two + "/item", "{\"title\":\"Four\"}");
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isPayloadTooLarge());
        assertThat(jdbcTemplate.queryForObject("select count(*) from project_snapshot", Long.class)).isEqualTo(1);
        mockMvc.perform(put("/api/project/" + pid(project)).header("Authorization", bearer(owner))
                        .contentType("application/json").content("{\"visibility\":\"private\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("private"));
    }

    @Test
    void blockedAndDeletedSourcesDoNotChangeHistoricalAttribution() throws Exception {
        User owner = createUser("blockedsource");
        User other = createUser("blockedforker");
        Project source = createProject(owner, "Public attribution", "private", "Description", null);
        publish(owner, source);
        String fork = postForProjectId(other, "/api/project/" + pid(source) + "/fork", "{}");
        jdbcTemplate.update("insert into blocked_user (id, blocker_id, blocked_id) values (nextval('blocked_user_seq'), ?, ?)",
                owner.getId(), other.getId());
        jdbcTemplate.update("update project set title = 'PRIVATE BLOCKED TITLE', visibility = 'private' where id = ?", source.getId());
        mockMvc.perform(get("/api/project/" + fork).header("Authorization", bearer(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.forkedFromTitle").value("Public attribution"));
        mockMvc.perform(delete("/api/project/" + pid(source)).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/project/" + fork).header("Authorization", bearer(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.forkedFromTitle").value("Public attribution"));
        mockMvc.perform(post("/api/project/" + fork + "/publish").header("Authorization", bearer(other)))
                .andExpect(status().isOk());
        assertThat(mockMvc.perform(get("/api/public/project/" + fork)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).doesNotContain("PRIVATE BLOCKED TITLE");
    }

    @Test
    void concurrentContentChangesCannotBypassCapturedBudget() throws Exception {
        User owner = createUser("captureconcurrency");
        Project project = createProject(owner, "Concurrent", "private", "Description", null);
        long trail = trail(owner, project, "Trail");
        long item = postForId(owner, "/api/trail/" + trail + "/item", "{\"title\":\"Note\"}");
        Long content = jdbcTemplate.queryForObject("select nextval('item_content_seq')", Long.class);
        jdbcTemplate.update("insert into item_content (id, content, extraction_epoch) values (?, 'published body', 0)", content);
        jdbcTemplate.update("update item set content_id = ? where id = ?", content, item);
        var updated = new java.util.concurrent.CountDownLatch(1);
        var commit = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var edit = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .executeWithoutResult(status -> {
                        jdbcTemplate.update("update item_content set content = ? where id = ?", "SECRET".repeat(1000), content);
                        updated.countDown();
                        try {
                            if (!commit.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }));
            assertThat(updated.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            publish(owner, project);
            commit.countDown();
            edit.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(mockMvc.perform(get("/api/public/project/" + pid(project))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).contains("published body").doesNotContain("SECRET");
            mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                    .andExpect(status().isPayloadTooLarge());
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void oldForkDoesNotFillMissingAttributionFromSource() throws Exception {
        User owner = createUser("legacyorigin");
        User other = createUser("legacyforker");
        Project source = createProject(owner, "SECRET ORIGINAL", "private");
        Project fork = createProject(other, "Old fork", "private", "Description", null);
        fork.setForkedFrom(source);
        projectRepository.save(fork);
        String body = mockMvc.perform(get("/api/project/" + pid(fork)).header("Authorization", bearer(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.forkedFromTitle").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SECRET ORIGINAL");
    }
}
