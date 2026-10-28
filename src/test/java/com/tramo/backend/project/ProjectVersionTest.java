package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.dto.*;
import com.tramo.backend.project.entity.*;
import com.tramo.backend.project.service.*;
import com.tramo.backend.trail.dto.*;
import com.tramo.backend.trail.entity.*;
import com.tramo.backend.trail.repository.*;
import com.tramo.backend.trail.service.*;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectVersionTest extends AbstractIntegrationTest {
    @Autowired ProjectVersionService versions;
    @Autowired ItemService items;
    @Autowired TrailService trails;
    @Autowired ItemRepository itemRepository;
    @Autowired TrailRepository trailRepository;
    @Autowired TrailItemRepository memberships;
    @Autowired ProjectService projects;

    record Fixture(User user, Project project, Long trail, Long item) {}
    Fixture fixture() {
        User user = createUser("historyowner");
        Project project = createProject(user, "Project", "PRIVATE");
        Trail trail = new Trail(); trail.setTitle("Trail"); trail.setProject(project);
        trail = trailRepository.save(trail);
        Item item = new Item(); item.setTitle("Note"); item.setTitleAlign("center"); item.setProject(project);
        ItemContent content = new ItemContent(); content.setContent(lexicalText("before")); item.setContent(content);
        item = itemRepository.save(item);
        TrailItem membership = new TrailItem(); membership.setTrail(trail); membership.setItem(item); memberships.save(membership);
        return new Fixture(user, project, trail.getId(), item.getId());
    }
    long autoCount(Fixture f) {
        return jdbcTemplate.queryForObject("select count(*) from project_version where project_id = ? and type = 'AUTO'", Long.class, f.project.getId());
    }
    void edit(Fixture f, String content) { items.updateContent(f.item, lexicalText(content), f.user); }

    @Test
    void creatingSnapshotRequiresEpochBeforeAndAfterRestore() throws Exception {
        var f = fixture();
        String url = "/api/project/" + pid(f.project) + "/history";
        for (int epoch = 0; epoch <= 1; epoch++) {
            for (String missing : new String[]{"", " "}) {
                mockMvc.perform(post(url).header("Authorization", bearer(f.user)).header("X-Project-Epoch", missing)
                                .contentType("application/json").content("{}"))
                        .andExpect(status().is(428)).andExpect(jsonPath("$.code").value("PROJECT_EPOCH_REQUIRED"));
            }
            mockMvc.perform(post(url).header("Authorization", bearer(f.user))
                            .contentType("application/json").content("{}"))
                    .andExpect(status().is(428));
            mockMvc.perform(post(url).header("Authorization", bearer(f.user)).header("X-Project-Epoch", Integer.toString(epoch))
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isOk());
            var manual = versions.create(f.project.getId(), null, f.user);
            versions.restore(f.project.getId(), manual.id(), f.user);
        }
        mockMvc.perform(post(url).header("Authorization", bearer(f.user)).header("X-Project-Epoch", "0")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void restoreRequiresEpochAndRejectsStaleRequestsWithoutChangingContent() throws Exception {
        var f = fixture();
        var manual = versions.create(f.project.getId(), null, f.user);
        edit(f, "important work");
        String url = "/api/project/" + pid(f.project) + "/history/" + manual.id() + "/restore";
        mockMvc.perform(post(url).header("Authorization", bearer(f.user)))
                .andExpect(status().is(428)).andExpect(jsonPath("$.code").value("PROJECT_EPOCH_REQUIRED"));
        assertThat(items.getContent(f.item, f.user).getContent()).isEqualTo(lexicalText("important work"));
        mockMvc.perform(post(url).header("Authorization", bearer(f.user)).header("X-Project-Epoch", "1"))
                .andExpect(status().isConflict());
        assertThat(items.getContent(f.item, f.user).getContent()).isEqualTo(lexicalText("important work"));
        mockMvc.perform(post(url).header("Authorization", bearer(f.user)).header("X-Project-Epoch", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.restoreEpoch").value(1));
        mockMvc.perform(post(url).header("Authorization", bearer(f.user)))
                .andExpect(status().is(428));
        mockMvc.perform(put("/api/item/" + f.item + "/content").header("Authorization", bearer(f.user)).header("X-Project-Epoch", "1")
                .contentType("application/json").content(new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("content", lexicalText("stale"), "extractionEpoch", 0, "expectedContent", ""))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("restored or extracted")));
    }

    @Test
    void firstEditCapturesPreviousStateAndFrequentSavesDoNotCaptureAgain() {
        var f = fixture(); edit(f, "after"); edit(f, "later");
        var rows = versions.list(f.project.getId(), null, f.user).versions();
        assertThat(rows).hasSize(1);
        assertThat(versions.detail(f.project.getId(), rows.get(0).id(), f.user).snapshot().items().get(0).content()).isEqualTo(lexicalText("before"));
        assertThat(items.getContent(f.item, f.user).getContent()).isEqualTo(lexicalText("later"));
    }

    @Test
    void identicalAutosaveDoesNotWriteDatesOrVersions() {
        var f = fixture();
        Date before = projectRepository.findById(f.project.getId()).orElseThrow().getLastEditedDate();
        edit(f, "before");
        assertThat(autoCount(f)).isZero();
        assertThat(projectRepository.findById(f.project.getId()).orElseThrow().getLastEditedDate()).isEqualTo(before);
    }

    @Test
    void inactivityAtFifteenMinutesCapturesPreviousState() {
        var f = fixture(); edit(f, "one");
        jdbcTemplate.update("update project set last_edited_date = current_timestamp - interval '15 minutes' where id = ?", f.project.getId());
        edit(f, "two");
        assertThat(autoCount(f)).isEqualTo(2);
        var latest = versions.list(f.project.getId(), null, f.user).versions().get(0);
        assertThat(versions.detail(f.project.getId(), latest.id(), f.user).snapshot().items().get(0).content()).isEqualTo(lexicalText("one"));
    }

    @Test
    void continuousEditingAtThirtyMinutesCapturesPreviousState() {
        var f = fixture(); edit(f, "one");
        jdbcTemplate.update("update project set last_auto_version_date = current_timestamp - interval '30 minutes' where id = ?", f.project.getId());
        edit(f, "two"); assertThat(autoCount(f)).isEqualTo(2);
    }

    @Test
    void returningToLastSnapshotDoesNotCreateDuplicateAuto() {
        var f = fixture(); edit(f, "one"); edit(f, "before");
        jdbcTemplate.update("update project set last_auto_version_date = current_timestamp - interval '30 minutes' where id = ?", f.project.getId());
        edit(f, "two"); assertThat(autoCount(f)).isEqualTo(1);
    }

    @Test
    void concurrentAutosavesCreateOneConsistentSnapshot() throws Exception {
        var f = fixture(); var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            var first = pool.submit(() -> { gate.await(); edit(f, "first"); return true; });
            var second = pool.submit(() -> { gate.await(); edit(f, "second"); return true; });
            gate.countDown(); first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
            assertThat(autoCount(f)).isEqualTo(1);
            assertThat(items.getContent(f.item, f.user).getContent()).isIn(lexicalText("first"), lexicalText("second"));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void retentionKeepsFiftyAutosAndProtectsManualVersions() {
        var f = fixture(); var manual = versions.create(f.project.getId(), "Keep", f.user);
        for (int i = 0; i < 53; i++) {
            jdbcTemplate.update("update project set last_auto_version_date = null where id = ?", f.project.getId());
            edit(f, "content " + i);
        }
        assertThat(autoCount(f)).isEqualTo(50);
        assertThat(versions.detail(f.project.getId(), manual.id(), f.user).version().name()).isEqualTo("Keep");
    }

    @Test
    void byteBudgetRemovesOldAutosWithoutRemovingManuals() {
        var f = fixture(); var manual = versions.create(f.project.getId(), "Keep", f.user);
        edit(f, "one");
        jdbcTemplate.update("update project_version set snapshot_bytes = 67108864 where type = 'AUTO'");
        jdbcTemplate.update("update project set last_auto_version_date = null where id = ?", f.project.getId());
        edit(f, "two");
        assertThat(autoCount(f)).isEqualTo(1);
        assertThat(versions.detail(f.project.getId(), manual.id(), f.user).version().type()).isEqualTo(ProjectVersion.Type.MANUAL);
    }

    @Test
    void restorePreservesSharedNoteIdentityAndRestoresStructureAndMetadata() {
        var f = fixture();
        TrailRequestDTO request = new TrailRequestDTO(); request.setTitle("Second");
        Long second = trails.create(f.project.getId(), request, f.user).getId();
        items.attachToTrail(second, f.item, f.user);
        ProjectRequestDTO metadata = new ProjectRequestDTO(); metadata.setGraphColors("{\"note\":\"red\"}"); projects.update(f.project.getId(), metadata, f.user);
        var manual = versions.create(f.project.getId(), "Original", f.user);
        edit(f, "changed"); trails.delete(f.trail, f.user);
        metadata.setGraphColors("{}"); projects.update(f.project.getId(), metadata, f.user);
        long epoch = versions.restore(f.project.getId(), manual.id(), f.user);
        assertThat(epoch).isEqualTo(1);
        assertThat(items.getContent(f.item, f.user).getContent()).isEqualTo(lexicalText("before"));
        assertThat(trails.getAllForProject(f.project.getId(), f.user)).hasSize(2);
        assertThat(itemRepository.findByProjectId(f.project.getId())).hasSize(1);
        assertThat(memberships.findByItemId(f.item)).hasSize(2);
        assertThat(projectRepository.findById(f.project.getId()).orElseThrow().getGraphColors()).isEqualTo("{\"note\":\"red\"}");
        assertThat(versions.list(f.project.getId(), null, f.user).versions().get(0).type()).isEqualTo(ProjectVersion.Type.RESTORE);
    }

    @Test
    void restoreRecreatesDeletedNotesAndDoesNotModifyOtherProjects() throws Exception {
        var f = fixture();
        User outsideOwner = createUser("outsideowner");
        var other = createProject(outsideOwner, "Other", "PRIVATE");
        Trail outside = new Trail(); outside.setTitle("Outside"); outside.setProject(other); outside = trailRepository.save(outside);
        TrailItem shared = new TrailItem(); shared.setTrail(outside); shared.setItem(itemRepository.findById(f.item).orElseThrow()); memberships.save(shared);
        var manual = versions.create(f.project.getId(), null, f.user);
        edit(f, "current shared");
        versions.restore(f.project.getId(), manual.id(), f.user);
        assertThat(items.getContent(f.item, outsideOwner).getContent()).isEqualTo(lexicalText("current shared"));
        var local = itemRepository.findByProjectId(f.project.getId());
        assertThat(local).hasSize(1);
        assertThat(local.get(0).getId()).isNotEqualTo(f.item);
        assertThat(local.get(0).getContent().getContent()).isEqualTo(lexicalText("before"));
        assertThat(items.getAllForTrail(outside.getId(), outsideOwner).get(0).id()).isEqualTo(f.item);
        mockMvc.perform(put("/api/item/" + f.item + "/content").header("Authorization", bearer(f.user))
                .header("X-Project-Epoch", "0").header("X-Project-Id", pid(f.project))
                .contentType("application/json").content(new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("content", lexicalText("obsolete from restored project"), "extractionEpoch", 0, "expectedContent", ""))))
                .andExpect(status().isConflict());
        items.updateContent(f.item, lexicalText("outside edit"), outsideOwner);
        assertThat(itemRepository.findByProjectId(f.project.getId()).get(0).getContent().getContent()).isEqualTo(lexicalText("before"));
    }

    @Test
    void deletedNotesAreRecreatedAndRestoreSafetyCanBeRestored() {
        var f = fixture(); var manual = versions.create(f.project.getId(), null, f.user);
        items.delete(f.item, f.user);
        versions.restore(f.project.getId(), manual.id(), f.user);
        assertThat(itemRepository.findByProjectId(f.project.getId())).hasSize(1);
        var safety = versions.list(f.project.getId(), null, f.user).versions().get(0);
        versions.restore(f.project.getId(), safety.id(), f.user);
        assertThat(itemRepository.findByProjectId(f.project.getId())).isEmpty();
    }

    @Test
    void ownershipAndVersionProjectAreChecked() throws Exception {
        var f = fixture(); var manual = versions.create(f.project.getId(), null, f.user);
        User stranger = createUser("historystanger");
        mockMvc.perform(get("/api/project/" + pid(f.project) + "/history").header("Authorization", bearer(stranger))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/project/" + pid(f.project) + "/history/" + manual.id() + "/restore").header("X-Project-Epoch", "0").header("Authorization", bearer(stranger))).andExpect(status().isNotFound());
        var other = createProject(f.user, "Other", "PRIVATE");
        mockMvc.perform(get("/api/project/" + pid(other) + "/history/" + manual.id()).header("Authorization", bearer(f.user))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/project/" + pid(f.project) + "/versions/" + manual.id())).andExpect(status().is4xxClientError());
    }

    @Test
    void obsoleteWritesAreRejectedEvenWhenRestoredContentMatches() throws Exception {
        var f = fixture(); var manual = versions.create(f.project.getId(), null, f.user);
        versions.restore(f.project.getId(), manual.id(), f.user);
        mockMvc.perform(put("/api/item/" + f.item + "/content").header("Authorization", bearer(f.user)).header("X-Project-Epoch", "0")
                .contentType("application/json").content(new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("content", lexicalText("stale"), "expectedContent", lexicalText("before"), "extractionEpoch", 1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_VERSION_CONFLICT"));
        mockMvc.perform(put("/api/item/" + f.item).header("Authorization", bearer(f.user)).contentType("application/json").content("{\"title\":\"stale\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/item/" + f.item + "/content").header("Authorization", bearer(f.user)).header("X-Project-Epoch", "1")
                .contentType("application/json").content(new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("content", lexicalText("fresh"), "expectedContent", lexicalText("before"), "extractionEpoch", 1))))
                .andExpect(status().isNoContent());
    }
    @Test
    void oversizedProjectKeepsAutosaveWorkingAndRecoversHistoryAfterShrinking() {
        var f = fixture();
        jdbcTemplate.update("update item_content set content = repeat('x', 8388609) where id = (select content_id from item where id = ?)", f.item);
        edit(f, "smaller");
        assertThat(versions.list(f.project.getId(), null, f.user).unavailable()).isTrue();
        assertThat(autoCount(f)).isZero();
        edit(f, "next");
        assertThat(versions.list(f.project.getId(), null, f.user).unavailable()).isFalse();
        assertThat(autoCount(f)).isEqualTo(1);
    }

    @Test
    void invalidRestoreRollsBackSafetyVersionAndAllChanges() {
        var f = fixture(); var manual = versions.create(f.project.getId(), null, f.user);
        jdbcTemplate.update("update project_version set snapshot = jsonb_set(snapshot::jsonb, '{items,0,content}', to_jsonb(repeat('x', 1048577)))::text where id = ?", manual.id());
        assertThatThrownBy(() -> versions.restore(f.project.getId(), manual.id(), f.user)).isInstanceOf(com.tramo.backend.exception.PayloadTooLargeException.class);
        assertThat(items.getContent(f.item, f.user).getContent()).isEqualTo(lexicalText("before"));
        assertThat(versions.list(f.project.getId(), null, f.user).versions()).hasSize(1);
        assertThat(projectRepository.findById(f.project.getId()).orElseThrow().getRestoreEpoch()).isZero();
    }

    @Test
    void structuralNoopsDoNotCreateVersionsOrChangeEditTime() {
        var f = fixture();
        ItemRequestDTO note = new ItemRequestDTO(); note.setTitle("Note"); items.update(f.item, note, f.user);
        items.attachToTrail(f.trail, f.item, f.user);
        items.reorderTrailItems(f.trail, List.of(f.item), f.user);
        trails.reorder(f.project.getId(), List.of(f.trail), f.user);
        assertThat(autoCount(f)).isZero();
        assertThat(projectRepository.findById(f.project.getId()).orElseThrow().getLastEditedDate()).isNull();
    }

    @Test
    void metadataListingDoesNotFetchSnapshotDocumentsAndSupportsPagination() throws Exception {
        var f = fixture();
        for (int index = 0; index < 22; index++) versions.create(f.project.getId(), "Version " + index, f.user);
        var page = versions.list(f.project.getId(), null, f.user);
        assertThat(page.versions()).hasSize(20);
        assertThat(versions.list(f.project.getId(), page.nextCursor(), f.user).versions()).hasSize(2);
        mockMvc.perform(get("/api/project/" + pid(f.project) + "/history").header("Authorization", bearer(f.user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.versions[0].authorUsername").value(f.user.getUsername()))
                .andExpect(jsonPath("$.versions[0].snapshot").doesNotExist());
    }

    @Test
    void concurrentRestoreAndAutosaveAlwaysLeaveRestoredState() throws Exception {
        var f = fixture(); var manual = versions.create(f.project.getId(), null, f.user);
        var pool = Executors.newFixedThreadPool(2); var gate = new CountDownLatch(1);
        try {
            var save = pool.submit(() -> {
                gate.await();
                return mockMvc.perform(put("/api/item/" + f.item + "/content").header("Authorization", bearer(f.user)).header("X-Project-Epoch", "0")
                        .contentType("application/json").content(new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("content", lexicalText("concurrent"), "expectedContent", lexicalText("before"), "extractionEpoch", 0))))
                        .andReturn().getResponse().getStatus();
            });
            var restore = pool.submit(() -> {
                gate.await();
                return mockMvc.perform(post("/api/project/" + pid(f.project) + "/history/" + manual.id() + "/restore")
                        .header("Authorization", bearer(f.user)).header("X-Project-Epoch", "0"))
                        .andReturn().getResponse().getStatus();
            });
            gate.countDown();
            assertThat(save.get(15, TimeUnit.SECONDS)).isIn(204, 409);
            assertThat(restore.get(15, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(items.getContent(f.item, f.user).getContent()).isEqualTo(lexicalText("before"));
            assertThat(projectRepository.findById(f.project.getId()).orElseThrow().getRestoreEpoch()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void restoreInvalidatesPendingStructureAndExtractionRequests() throws Exception {
        var f = fixture(); var manual = versions.create(f.project.getId(), null, f.user);
        versions.restore(f.project.getId(), manual.id(), f.user);
        mockMvc.perform(put("/api/project/" + pid(f.project) + "/trail/order").header("Authorization", bearer(f.user)).header("X-Project-Epoch", "0")
                .contentType("application/json").content("{\"trailIds\":[" + f.trail + "]}")).andExpect(status().isConflict());
        mockMvc.perform(post("/api/project/" + pid(f.project) + "/item/" + f.item + "/extract").header("Authorization", bearer(f.user)).header("X-Project-Epoch", "0")
                .contentType("application/json").content(new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("operationId", UUID.randomUUID(), "title", "Extract", "expectedContent", lexicalText("before"), "sourceContent", lexicalText("source"), "extractedContent", lexicalText("selection"), "extractionEpoch", 1, "appendToTrail", false))))
                .andExpect(status().isConflict());
    }

    @Test
    void recreatedNotesRetargetInternalMentionsAndKeepTrailOrder() {
        var f = fixture();
        ItemRequestDTO request = new ItemRequestDTO(); request.setTitle("Target");
        Long target = items.create(f.trail, request, f.user).getId();
        String content = "{\"root\":{\"type\":\"root\",\"children\":[{\"type\":\"link\",\"url\":\"#\",\"rel\":\"tramo-idea:" + target + "\",\"children\":[]}]}}";
        items.updateContent(f.item, content, f.user);
        var manual = versions.create(f.project.getId(), "Links", f.user);
        items.delete(f.item, f.user); items.delete(target, f.user);
        versions.restore(f.project.getId(), manual.id(), f.user);
        var restored = items.getAllForTrail(f.trail, f.user);
        assertThat(restored).extracting(TrailItemDTO::title).containsExactly("Note", "Target");
        assertThat(items.getContent(restored.get(0).id(), f.user).getContent()).contains("tramo-idea:" + restored.get(1).id()).doesNotContain("tramo-idea:" + target + "\"");
    }

}
