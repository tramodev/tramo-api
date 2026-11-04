// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectMemberAccessTest extends AbstractIntegrationTest {

    private void addMember(User owner, Project project, User member, String role) throws Exception {
        mockMvc.perform(post("/api/project/" + pid(project) + "/members")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"role\":\"%s\"}".formatted(member.getUsername(), role)))
                .andExpect(status().isOk());
    }

    private long createTrail(User user, Project project) throws Exception {
        return postForId(user, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Trail\"}");
    }

    private long createItem(User user, long trailId) throws Exception {
        return postForId(user, "/api/trail/" + trailId + "/item", "{\"title\":\"Note\"}");
    }

    private org.springframework.test.web.servlet.ResultActions saveContent(User user, long itemId, String text, String expected) throws Exception {
        return mockMvc.perform(put("/api/item/" + itemId + "/content")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(contentRequest(lexicalText(text), expected)));
    }

    @Test
    void strangerGetsNotFoundOnPrivateProject() throws Exception {
        User owner = createUser("pmaowner1");
        User stranger = createUser("pmastranger1");
        Project project = createProject(owner, "Private", "private");
        long item = createItem(owner, createTrail(owner, project));

        mockMvc.perform(get("/api/project/" + pid(project)).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/item/" + item + "/content").header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/project/" + pid(project) + "/members").header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewerReadsButCannotWrite() throws Exception {
        User owner = createUser("pmaowner2");
        User viewer = createUser("pmaviewer2");
        Project project = createProject(owner, "Shared", "private");
        long trail = createTrail(owner, project);
        long item = createItem(owner, trail);
        addMember(owner, project, viewer, "VIEWER");

        mockMvc.perform(get("/api/project/" + pid(project)).header("Authorization", bearer(viewer)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/item/" + item + "/content").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk());
        saveContent(viewer, item, "nope", "").andExpect(status().isForbidden());
        mockMvc.perform(post("/api/project/" + pid(project) + "/trail")
                        .header("Authorization", bearer(viewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void editorWritesAndOwnerPaysQuota() throws Exception {
        User owner = createUser("pmaowner3");
        User editor = createUser("pmaeditor3");
        Project project = createProject(owner, "Shared", "private");
        long trail = createTrail(owner, project);
        long item = createItem(owner, trail);
        addMember(owner, project, editor, "EDITOR");

        saveContent(editor, item, "edited by editor", "").andExpect(status().isNoContent());
        long newTrail = createTrail(editor, project);
        createItem(editor, newTrail);

        mockMvc.perform(get("/api/item/" + item + "/content").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(lexicalText("edited by editor")));
    }

    @Test
    void editorCannotRunOwnerActions() throws Exception {
        User owner = createUser("pmaowner4");
        User editor = createUser("pmaeditor4");
        User other = createUser("pmaother4");
        Project project = createProject(owner, "Shared", "private", "desc", null);
        addMember(owner, project, editor, "EDITOR");

        mockMvc.perform(delete("/api/project/" + pid(project)).header("Authorization", bearer(editor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(editor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(editor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"UNLISTED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(editor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed by editor\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + pid(project) + "/members")
                        .header("Authorization", bearer(editor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"role\":\"VIEWER\"}".formatted(other.getUsername())))
                .andExpect(status().isForbidden());
    }

    @Test
    void removingAndBlockingRevokeAccess() throws Exception {
        User owner = createUser("pmaowner5");
        User first = createUser("pmafirst5");
        User second = createUser("pmasecond5");
        Project project = createProject(owner, "Shared", "private");
        addMember(owner, project, first, "EDITOR");
        addMember(owner, project, second, "VIEWER");

        mockMvc.perform(delete("/api/project/" + pid(project) + "/members/" + first.getUsername()).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/project/" + pid(project)).header("Authorization", bearer(first)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/users/" + owner.getUsername() + "/block").header("Authorization", bearer(second)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/project/" + pid(project)).header("Authorization", bearer(second)))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerCannotAddSelfAndMembershipIsCapped() throws Exception {
        User owner = createUser("pmaowner6");
        Project project = createProject(owner, "Shared", "private");
        mockMvc.perform(post("/api/project/" + pid(project) + "/members")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"role\":\"EDITOR\"}".formatted(owner.getUsername())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/project/" + pid(project) + "/members")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"role\":\"OWNER\"}".formatted(createUser("pmaother6").getUsername())))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM project_member", Long.class)).isZero();
    }
}
