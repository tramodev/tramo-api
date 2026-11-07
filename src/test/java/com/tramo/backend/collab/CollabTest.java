// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.collab.dto.CollabStoreRequest;
import com.tramo.backend.collab.service.CollabClient;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CollabTest extends AbstractIntegrationTest {
    @MockitoBean
    private CollabClient collabClient;
    @org.springframework.beans.factory.annotation.Autowired
    private com.tramo.backend.collab.service.CollabService collab;
    @org.springframework.beans.factory.annotation.Autowired
    private com.tramo.backend.project.service.ProjectVersionService versions;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STATE = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});

    private void addMember(User owner, Project project, User member, String role) throws Exception {
        mockMvc.perform(post("/api/project/" + pid(project) + "/members")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"role\":\"%s\"}".formatted(member.getUsername(), role)))
                .andExpect(status().isOk());
    }

    private long createItem(User owner, Project project) throws Exception {
        long trail = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Trail\"}");
        return postForId(owner, "/api/trail/" + trail + "/item", "{\"title\":\"Note\"}");
    }

    private ResultActions token(User user, long item) throws Exception {
        return mockMvc.perform(post("/api/collab/token")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\":" + item + "}"));
    }

    private JsonNode claims(User user, long item) throws Exception {
        String token = MAPPER.readTree(token(user, item).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("token").asString();
        return MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private ResultActions projectToken(User user, Project project) throws Exception {
        return mockMvc.perform(post("/api/collab/project-token")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"" + pid(project) + "\"}"));
    }

    private JsonNode projectClaims(User user, Project project) throws Exception {
        String token = MAPPER.readTree(projectToken(user, project).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("token").asString();
        return MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private static Map<String, Object> projectSession(User user, Project project, String perm) {
        var session = new java.util.HashMap<String, Object>(Map.of("userId", user.getId(), "projectId", project.getId(), "perm", perm));
        session.put("sv", java.util.Objects.toString(user.getSessionVersion(), null));
        return session;
    }

    private ResultActions load(long item) throws Exception {
        return mockMvc.perform(get("/internal/collab/items/" + item).header("X-Collab-Secret", TEST_COLLAB_SECRET));
    }

    private ResultActions store(long item, User author, String content, int epoch, String bootstrapHash) throws Exception {
        var body = new java.util.HashMap<String, Object>(Map.of("state", STATE, "content", content, "extractionEpoch", epoch, "authorId", author.getId()));
        if (bootstrapHash != null) body.put("bootstrapHash", bootstrapHash);
        return mockMvc.perform(put("/internal/collab/items/" + item)
                .header("X-Collab-Secret", TEST_COLLAB_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body)));
    }

    private ResultActions check(Object sessions) throws Exception {
        return mockMvc.perform(post("/internal/collab/check")
                .header("X-Collab-Secret", TEST_COLLAB_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(sessions)));
    }

    private static Map<String, Object> session(User user, long item, String perm) {
        var session = new java.util.HashMap<String, Object>(Map.of("userId", user.getId(), "itemId", item, "perm", perm));
        session.put("sv", java.util.Objects.toString(user.getSessionVersion(), null));
        return session;
    }

    private static String hash(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private long structureVersion(long project) {
        return jdbcTemplate.queryForObject("SELECT structure_version FROM project WHERE id = ?", Long.class, project);
    }

    private ResultActions renameItem(User user, long item, String title) throws Exception {
        return mockMvc.perform(put("/api/item/" + item).header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"));
    }

    @Test
    void structuralChangesBumpTheVersionAndNotifyButContentSavesDoNot() throws Exception {
        User owner = createUser("collabstructure1");
        Project project = createProject(owner, "Structure", "private");
        long start = structureVersion(project.getId());
        long trail = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Trail\"}");
        long first = postForId(owner, "/api/trail/" + trail + "/item", "{\"title\":\"One\"}");
        long second = postForId(owner, "/api/trail/" + trail + "/item", "{\"title\":\"Two\"}");
        assertThat(structureVersion(project.getId())).isEqualTo(start + 3);
        verify(collabClient, atLeastOnce()).structureAfterCommit(project.getId());

        long beforeRename = structureVersion(project.getId());
        renameItem(owner, first, "Renamed").andExpect(status().isOk());
        assertThat(structureVersion(project.getId())).isEqualTo(beforeRename + 1);

        clearInvocations(collabClient);
        long unchanged = structureVersion(project.getId());
        renameItem(owner, first, "Renamed").andExpect(status().isOk());
        mockMvc.perform(put("/api/item/" + first + "/content").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(contentRequest(lexicalText("body"))))
                .andExpect(status().is2xxSuccessful());
        assertThat(structureVersion(project.getId())).isEqualTo(unchanged);
        verify(collabClient, never()).structureAfterCommit(project.getId());

        mockMvc.perform(put("/api/trail/" + trail + "/item/order").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemIds\":[" + second + "," + first + "]}"))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(delete("/api/trail/" + trail).header("Authorization", bearer(owner)))
                .andExpect(status().is2xxSuccessful());
        assertThat(structureVersion(project.getId())).isEqualTo(unchanged + 2);
        verify(collabClient, atLeastOnce()).structureAfterCommit(project.getId());
    }

    @Test
    void renamingOrDeletingASharedNoteBumpsEveryProjectItBelongsTo() throws Exception {
        User owner = createUser("collabshared1");
        User other = createUser("collabshared2");
        Project home = createProject(owner, "Home", "private");
        Project away = createProject(other, "Away", "private");
        long item = createItem(owner, home);
        long awayTrail = postForId(other, "/api/project/" + pid(away) + "/trail", "{\"title\":\"Away trail\"}");
        jdbcTemplate.update("INSERT INTO trail_item (id, trail_id, item_id, order_index) VALUES (nextval('trail_item_seq'), ?, ?, 0)", awayTrail, item);
        clearInvocations(collabClient);
        long homeBefore = structureVersion(home.getId());
        long awayBefore = structureVersion(away.getId());

        renameItem(owner, item, "Shared rename").andExpect(status().isOk());
        assertThat(structureVersion(home.getId())).isEqualTo(homeBefore + 1);
        assertThat(structureVersion(away.getId())).isEqualTo(awayBefore + 1);
        verify(collabClient).structureAfterCommit(home.getId());
        verify(collabClient).structureAfterCommit(away.getId());

        mockMvc.perform(delete("/api/item/" + item).header("Authorization", bearer(owner))).andExpect(status().is2xxSuccessful());
        assertThat(structureVersion(home.getId())).isEqualTo(homeBefore + 2);
        assertThat(structureVersion(away.getId())).isEqualTo(awayBefore + 2);
    }

    @Test
    void internalStructureReadsAConsistentSnapshot() throws Exception {
        User owner = createUser("collabstructure3");
        Project project = createProject(owner, "Snapshot", "private");
        long item = createItem(owner, project);
        mockMvc.perform(get("/internal/collab/projects/" + project.getId() + "/structure")).andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/collab/projects/" + project.getId() + "/structure").header("X-Collab-Secret", "wrong"))
                .andExpect(status().isForbidden());
        long version = structureVersion(project.getId());
        mockMvc.perform(get("/internal/collab/projects/" + project.getId() + "/structure").header("X-Collab-Secret", TEST_COLLAB_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version))
                .andExpect(jsonPath("$.restoreEpoch").value(0))
                .andExpect(jsonPath("$.trails[0].itemIds[0]").value(item))
                .andExpect(jsonPath("$.items[0].id").value(item))
                .andExpect(jsonPath("$.items[0].title").value("Note"));
        mockMvc.perform(get("/api/project/" + pid(project) + "/editor").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.structureVersion").value(version));
        mockMvc.perform(post("/internal/collab/projects/structure-versions").header("X-Collab-Secret", TEST_COLLAB_SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content("[" + project.getId() + ", 999999999]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['" + project.getId() + "']").value(version))
                .andExpect(jsonPath("$['999999999']").doesNotExist());
        mockMvc.perform(post("/internal/collab/projects/structure-versions")
                        .contentType(MediaType.APPLICATION_JSON).content("[" + project.getId() + "]"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/collab/projects/999999999/structure").header("X-Collab-Secret", TEST_COLLAB_SECRET))
                .andExpect(status().isNotFound());
    }

    private String storedContent(long item) {
        return jdbcTemplate.queryForObject("SELECT c.content FROM item i JOIN item_content c ON c.id = i.content_id WHERE i.id = ?", String.class, item);
    }

    @Test
    void collabTokensHaveTheirOwnLimitAndDoNotStarveEdits() throws Exception {
        User owner = createUser("collabtokenlimit");
        Project project = createProject(owner, "Tokens", "private");
        long item = createItem(owner, project);
        for (int i = 0; i < 70; i++) token(owner, item).andExpect(status().isOk());
        postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Still editable\"}");
    }

    @Test
    void tokenFollowsProjectRole() throws Exception {
        User owner = createUser("collabowner1");
        User editor = createUser("collabeditor1");
        User viewer = createUser("collabviewer1");
        User stranger = createUser("collabstranger1");
        Project project = createProject(owner, "Shared", "private");
        long item = createItem(owner, project);
        addMember(owner, project, editor, "EDITOR");
        addMember(owner, project, viewer, "VIEWER");

        assertThat(claims(owner, item).path("perm").asString()).isEqualTo("edit");
        assertThat(claims(editor, item).path("perm").asString()).isEqualTo("edit");
        JsonNode view = claims(viewer, item);
        assertThat(view.path("perm").asString()).isEqualTo("view");
        assertThat(view.path("item").asString()).isEqualTo(Long.toString(item));
        assertThat(view.path("sub").asString()).isEqualTo(viewer.getId().toString());
        assertThat(view.path("aud").toString()).contains("tramo-collab");
        token(stranger, item).andExpect(status().isNotFound());
    }

    @Test
    void projectTokenFollowsProjectRole() throws Exception {
        User owner = createUser("collabprojowner");
        User editor = createUser("collabprojeditor");
        User viewer = createUser("collabprojviewer");
        User stranger = createUser("collabprojstranger");
        Project project = createProject(owner, "Presence", "private");
        addMember(owner, project, editor, "EDITOR");
        addMember(owner, project, viewer, "VIEWER");

        JsonNode own = projectClaims(owner, project);
        assertThat(own.path("perm").asString()).isEqualTo("edit");
        assertThat(own.path("role").asString()).isEqualTo("OWNER");
        assertThat(own.path("project").asString()).isEqualTo(project.getId().toString());
        assertThat(own.has("item")).isFalse();
        assertThat(own.path("name").asString()).isEqualTo(owner.getUsername());
        assertThat(projectClaims(editor, project).path("perm").asString()).isEqualTo("edit");
        JsonNode view = projectClaims(viewer, project);
        assertThat(view.path("perm").asString()).isEqualTo("view");
        assertThat(view.path("role").asString()).isEqualTo("VIEWER");
        projectToken(stranger, project).andExpect(status().isNotFound());
    }

    @Test
    void projectTokensDoNotStarveEdits() throws Exception {
        User owner = createUser("collabprojlimit");
        Project project = createProject(owner, "Tokens", "private");
        for (int i = 0; i < 70; i++) projectToken(owner, project).andExpect(status().isOk());
        postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Still editable\"}");
    }

    @Test
    void checkFlagsLostProjectAccess() throws Exception {
        User owner = createUser("collabprojowner7");
        User editor = createUser("collabprojeditor7");
        User removed = createUser("collabprojremoved7");
        Project project = createProject(owner, "Presence", "private");
        addMember(owner, project, editor, "EDITOR");
        addMember(owner, project, removed, "VIEWER");
        mockMvc.perform(delete("/api/project/" + pid(project) + "/members/" + removed.getUsername())
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        check(java.util.List.of(
                projectSession(owner, project, "edit"),
                projectSession(editor, project, "edit"),
                projectSession(removed, project, "view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.contains(2)));
        check(java.util.List.of(Map.of("userId", owner.getId(), "perm", "edit", "sv", "x")))
                .andExpect(status().isBadRequest());
        check(java.util.Collections.singletonList(null))
                .andExpect(status().isBadRequest());
    }

    @Test
    void internalEndpointsRequireSecret() throws Exception {
        User owner = createUser("collabowner2");
        long item = createItem(owner, createProject(owner, "Private", "private"));

        mockMvc.perform(get("/internal/collab/items/" + item)).andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/collab/items/" + item).header("X-Collab-Secret", "wrong")).andExpect(status().isForbidden());
        load(item).andExpect(status().isOk());
    }

    @Test
    void bootstrapThenStoreUpdatesContentAndState() throws Exception {
        User owner = createUser("collabowner3");
        User editor = createUser("collabeditor3");
        Project project = createProject(owner, "Shared", "private");
        long item = createItem(owner, project);
        addMember(owner, project, editor, "EDITOR");

        load(item).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").doesNotExist())
                .andExpect(jsonPath("$.content").value(""))
                .andExpect(jsonPath("$.extractionEpoch").value(0));
        store(item, editor, lexicalText("hello"), 0, null).andExpect(status().isConflict());
        store(item, editor, lexicalText("hello"), 0, hash("")).andExpect(status().isNoContent());
        store(item, editor, lexicalText("again"), 0, hash(lexicalText("hello"))).andExpect(status().isConflict());
        store(item, editor, lexicalText("world"), 0, null).andExpect(status().isNoContent());

        assertThat(storedContent(item)).isEqualTo(lexicalText("world"));
        load(item).andExpect(jsonPath("$.state").value(STATE));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM project_version WHERE project_id = ?", Long.class, project.getId())).isPositive();
    }

    @Test
    void bootstrapRejectsContentChangedSinceLoad() throws Exception {
        User owner = createUser("collabowner4");
        long item = createItem(owner, createProject(owner, "Private", "private"));

        store(item, owner, lexicalText("hello"), 0, hash(lexicalText("stale"))).andExpect(status().isConflict());
        assertThat(storedContent(item)).isNotEqualTo(lexicalText("hello"));
    }

    @Test
    void storeRejectsViewerInvalidContentAndStaleEpoch() throws Exception {
        User owner = createUser("collabowner5");
        User viewer = createUser("collabviewer5");
        Project project = createProject(owner, "Shared", "private");
        long item = createItem(owner, project);
        addMember(owner, project, viewer, "VIEWER");

        store(item, viewer, lexicalText("nope"), 0, hash("")).andExpect(status().isForbidden());
        store(item, owner, "{\"root\":{\"type\":\"script\"}}", 0, hash("")).andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM item_ydoc", Long.class)).isZero();

        store(item, owner, lexicalText("hello"), 0, hash("")).andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE item_content SET extraction_epoch = 1 WHERE id = (SELECT content_id FROM item WHERE id = ?)", item);
        store(item, owner, lexicalText("late"), 0, null).andExpect(status().isConflict());
        load(item).andExpect(jsonPath("$.state").doesNotExist()).andExpect(jsonPath("$.extractionEpoch").value(1));
        store(item, owner, lexicalText("fresh"), 1, hash(lexicalText("hello"))).andExpect(status().isNoContent());
        load(item).andExpect(jsonPath("$.state").value(STATE));
    }

    @Test
    void checkFlagsLostAccess() throws Exception {
        User owner = createUser("collabowner6");
        User editor = createUser("collabeditor6");
        User removed = createUser("collabremoved6");
        Project project = createProject(owner, "Shared", "private");
        long item = createItem(owner, project);
        long deleted = createItem(owner, project);
        addMember(owner, project, editor, "EDITOR");
        addMember(owner, project, removed, "EDITOR");
        mockMvc.perform(patch("/api/project/" + pid(project) + "/members/" + editor.getUsername())
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/project/" + pid(project) + "/members/" + removed.getUsername())
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/item/" + deleted).header("Authorization", bearer(owner)))
                .andExpect(status().is2xxSuccessful());

        check(java.util.List.of(
                session(owner, item, "edit"),
                session(editor, item, "edit"),
                session(editor, item, "view"),
                session(removed, item, "edit"),
                session(owner, deleted, "edit"),
                Map.of("userId", owner.getId(), "itemId", item, "sv", "stale", "perm", "edit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.contains(1, 3, 4, 5)));
    }

    @Test
    void lostAccessRevokesCollabSessions() throws Exception {
        User owner = createUser("collabowner7");
        User editor = createUser("collabeditor7");
        User viewer = createUser("collabviewer7");
        Project project = createProject(owner, "Shared", "private");
        addMember(owner, project, editor, "EDITOR");
        addMember(owner, project, viewer, "VIEWER");

        mockMvc.perform(patch("/api/project/" + pid(project) + "/members/" + viewer.getUsername())
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isOk());
        verify(collabClient, never()).revokeAfterCommit(viewer.getId());
        mockMvc.perform(patch("/api/project/" + pid(project) + "/members/" + editor.getUsername())
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());
        verify(collabClient).revokeAfterCommit(editor.getId());
        mockMvc.perform(delete("/api/project/" + pid(project) + "/members/" + viewer.getUsername())
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        verify(collabClient).revokeAfterCommit(viewer.getId());
    }

    @Test
    void restoreResetsNotesItDeletes() throws Exception {
        User owner = createUser("collabowner8");
        Project project = createProject(owner, "Private", "private");
        long kept = createItem(owner, project);
        long version = MAPPER.readTree(mockMvc.perform(post("/api/project/" + pid(project) + "/history")
                        .header("Authorization", bearer(owner)).header("X-Project-Epoch", "0")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asLong();
        long removed = createItem(owner, project);
        clearInvocations(collabClient);
        long before = structureVersion(project.getId());

        mockMvc.perform(post("/api/project/" + pid(project) + "/history/" + version + "/restore")
                        .header("Authorization", bearer(owner)).header("X-Project-Epoch", "0"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM item WHERE id = ?", Long.class, removed)).isZero();
        verify(collabClient).resetAfterCommit(argThat(ids -> ids.contains(kept) && ids.contains(removed)));
        verify(collabClient).structureAfterCommit(project.getId());
        assertThat(structureVersion(project.getId())).isGreaterThan(before);
    }

    @Test
    void restoreTellsOpenEditorsOfMembersToRefresh() throws Exception {
        User owner = createUser("collabowner11");
        User editor = createUser("collabeditor11");
        Project project = createProject(owner, "Private", "private");
        addMember(owner, project, editor, "EDITOR");
        createItem(owner, project);
        long version = MAPPER.readTree(mockMvc.perform(post("/api/project/" + pid(project) + "/history")
                        .header("Authorization", bearer(owner)).header("X-Project-Epoch", "0")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asLong();
        MvcResult stream = mockMvc.perform(get("/api/notifications/stream").header("Authorization", bearer(editor)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();

        mockMvc.perform(post("/api/project/" + pid(project) + "/history/" + version + "/restore")
                        .header("Authorization", bearer(owner)).header("X-Project-Epoch", "0"))
                .andExpect(status().isOk());
        assertThat(stream.getResponse().getContentAsString()).contains("event:project-restored").contains(pid(project));
    }

    @Test
    void storeReturnsTheContentWhenUnsafeLinksWereRemoved() throws Exception {
        User owner = createUser("collabowner12");
        Project project = createProject(owner, "Private", "private");
        long item = createItem(owner, project);
        String unsafe = "{\"root\":{\"type\":\"root\",\"children\":[{\"type\":\"paragraph\",\"children\":[{\"type\":\"link\",\"url\":\"javascript:alert(1)\",\"children\":[{\"type\":\"text\",\"text\":\"label\"}]}]}]}}";
        String body = store(item, owner, unsafe, 0, hash("")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String sanitized = MAPPER.readTree(body).path("content").asString();
        assertThat(sanitized).doesNotContain("javascript").contains("label");
        store(item, owner, sanitized, 0, null).andExpect(status().isNoContent());
    }

    @Test
    void restPutIsRejectedOnceNoteIsCollaborative() throws Exception {
        User owner = createUser("collabowner9");
        long item = createItem(owner, createProject(owner, "Private", "private"));

        mockMvc.perform(put("/api/item/" + item + "/content").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(contentRequest(lexicalText("draft"))))
                .andExpect(status().isNoContent());
        store(item, owner, lexicalText("live"), 0, hash(lexicalText("draft"))).andExpect(status().isNoContent());
        mockMvc.perform(put("/api/item/" + item + "/content").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(contentRequest(lexicalText("late"), lexicalText("live"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLAB_ACTIVE"));
        assertThat(storedContent(item)).isEqualTo(lexicalText("live"));
    }

    @Test
    void concurrentStoreAndRestoreSerialize() throws Exception {
        User owner = createUser("collabowner10");
        Project project = createProject(owner, "Private", "private");
        long item = createItem(owner, project);
        store(item, owner, lexicalText("before"), 0, hash("")).andExpect(status().isNoContent());
        var version = versions.create(project.getId(), null, owner);
        store(item, owner, lexicalText("after"), 0, null).andExpect(status().isNoContent());

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var latch = new java.util.concurrent.CountDownLatch(1);
            var storing = pool.submit(() -> {
                latch.await();
                try {
                    collab.store(item, new CollabStoreRequest(new byte[]{9}, lexicalText("racing"), 0, owner.getId(), null));
                    return true;
                } catch (com.tramo.backend.exception.ExtractionConflictException restoredFirst) {
                    return false;
                }
            });
            var restoring = pool.submit(() -> { latch.await(); return versions.restore(project.getId(), version.id(), owner); });
            latch.countDown();
            storing.get(20, java.util.concurrent.TimeUnit.SECONDS);
            restoring.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(storedContent(item)).isEqualTo(lexicalText("before"));
        assertThat(jdbcTemplate.queryForObject("SELECT c.extraction_epoch FROM item i JOIN item_content c ON c.id = i.content_id WHERE i.id = ?", Integer.class, item)).isEqualTo(1);
        load(item).andExpect(jsonPath("$.state").doesNotExist());
    }

    @Test
    void downgradingTheShareLinkRevokesSessionsOfMembersWhoJoinedThroughIt() throws Exception {
        User owner = createUser("collabowner11");
        User guest = createUser("collabguest11");
        Project project = createProject(owner, "Shared", "private");
        String token = MAPPER.readTree(mockMvc.perform(put("/api/project/" + pid(project) + "/share-link")
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("token").asString();
        mockMvc.perform(post("/api/share-links/accept").header("Authorization", bearer(guest))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/project/" + pid(project) + "/share-link")
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());
        verify(collabClient).revokeAfterCommit(guest.getId());
    }
}
