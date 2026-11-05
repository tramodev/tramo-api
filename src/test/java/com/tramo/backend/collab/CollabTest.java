// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CollabTest extends AbstractIntegrationTest {
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

    private String storedContent(long item) {
        return jdbcTemplate.queryForObject("SELECT c.content FROM item i JOIN item_content c ON c.id = i.content_id WHERE i.id = ?", String.class, item);
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
}
