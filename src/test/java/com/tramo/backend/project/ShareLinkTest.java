// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project;

import com.jayway.jsonpath.JsonPath;
import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.auth.service.OpaqueToken;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ShareLinkTest extends AbstractIntegrationTest {

    private MvcResult setLink(User owner, Project project, String role) throws Exception {
        return mockMvc.perform(put("/api/project/" + pid(project) + "/share-link")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"%s\"}".formatted(role)))
                .andExpect(status().isOk()).andReturn();
    }

    private String token(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private ResultActions clearLink(User owner, Project project) throws Exception {
        return mockMvc.perform(delete("/api/project/" + pid(project) + "/share-link").header("Authorization", bearer(owner)));
    }

    private ResultActions accept(User user, String token) throws Exception {
        return mockMvc.perform(post("/api/share-links/accept")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\"}".formatted(token)));
    }

    private String role(Project project, User user) {
        return jdbcTemplate.queryForList("SELECT role FROM project_member WHERE project_id = ? AND user_id = ?",
                String.class, project.getId(), user.getId()).stream().findFirst().orElse(null);
    }

    @Test
    void linkIsStoredHashedAndEncryptedAndTheOwnerCanCopyItAgain() throws Exception {
        User owner = createUser("slowner1");
        User guest = createUser("slguest1");
        Project project = createProject(owner, "Shared", "private");
        mockMvc.perform(get("/api/project/" + pid(project) + "/share-link").header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        String token = token(setLink(owner, project, "VIEWER"));

        var stored = jdbcTemplate.queryForMap("SELECT token_hash, token_ciphertext FROM project_share_link");
        assertThat(stored.get("token_hash")).isEqualTo(OpaqueToken.hash(token));
        assertThat((String) stored.get("token_ciphertext")).isNotBlank().doesNotContain(token);
        accept(guest, token).andExpect(status().isOk()).andExpect(jsonPath("$.projectId").value(pid(project)));
        assertThat(role(project, guest)).isEqualTo("VIEWER");
        mockMvc.perform(get("/api/project/" + pid(project) + "/share-link").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.useCount").value(1))
                .andExpect(jsonPath("$.token").value(token));
    }

    @Test
    void changingTheLinkRoleKeepsTheTokenAndUpdatesOnlyMembersWhoJoinedThroughIt() throws Exception {
        User owner = createUser("slowner7");
        User viaLink = createUser("slvialink7");
        User direct = createUser("sldirect7");
        Project project = createProject(owner, "Shared", "private");
        String token = token(setLink(owner, project, "EDITOR"));
        accept(viaLink, token).andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + pid(project) + "/members")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"role\":\"EDITOR\"}".formatted(direct.getUsername())))
                .andExpect(status().isOk());

        assertThat(token(setLink(owner, project, "VIEWER"))).isEqualTo(token);
        assertThat(role(project, viaLink)).isEqualTo("VIEWER");
        assertThat(role(project, direct)).isEqualTo("EDITOR");
        accept(createUser("sllate7"), token).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM project_share_link WHERE revoked_at IS NULL", Long.class)).isEqualTo(1);
    }

    @Test
    void acceptRequiresLoginAndRejectsUnknownTokens() throws Exception {
        User owner = createUser("slowner2");
        Project project = createProject(owner, "Shared", "private");
        String token = token(setLink(owner, project, "EDITOR"));
        mockMvc.perform(post("/api/share-links/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isUnauthorized());
        accept(createUser("slguest2"), "x" + token.substring(1)).andExpect(status().isNotFound());
    }

    @Test
    void expiredLinkIsRejected() throws Exception {
        User owner = createUser("slowner3");
        Project project = createProject(owner, "Shared", "private");
        String token = token(setLink(owner, project, "VIEWER"));
        jdbcTemplate.update("UPDATE project_share_link SET expires_at = now() - interval '1 minute'");
        accept(createUser("slguest3"), token).andExpect(status().isNotFound());
    }

    @Test
    void revokingRemovesOnlyMembersWhoJoinedThroughTheLink() throws Exception {
        User owner = createUser("slowner4");
        User viaLink = createUser("slvialink4");
        User direct = createUser("sldirect4");
        User promoted = createUser("slpromoted4");
        Project project = createProject(owner, "Shared", "private");
        MvcResult link = setLink(owner, project, "EDITOR");
        accept(viaLink, token(link)).andExpect(status().isOk());
        accept(promoted, token(link)).andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + pid(project) + "/members")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"role\":\"VIEWER\"}".formatted(direct.getUsername())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + pid(project) + "/members")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"role\":\"EDITOR\"}".formatted(promoted.getUsername())))
                .andExpect(status().isOk());
        accept(direct, token(link)).andExpect(status().isOk());
        assertThat(role(project, direct)).isEqualTo("VIEWER");

        MvcResult stream = mockMvc.perform(get("/api/notifications/stream").header("Authorization", bearer(viaLink)))
                .andExpect(request().asyncStarted()).andReturn();
        clearLink(owner, project).andExpect(status().isNoContent());

        assertThat(role(project, viaLink)).isNull();
        assertThat(role(project, direct)).isEqualTo("VIEWER");
        assertThat(role(project, promoted)).isEqualTo("EDITOR");
        assertThat(stream.getResponse().getContentAsString()).contains("event:access-revoked");
        accept(createUser("sllate4"), token(link)).andExpect(status().isNotFound());
    }

    @Test
    void restrictingThenSharingAgainIssuesANewTokenAndUnlinksOldMembers() throws Exception {
        User owner = createUser("slowner5");
        User guest = createUser("slguest5");
        Project project = createProject(owner, "Shared", "private");
        String oldToken = token(setLink(owner, project, "VIEWER"));
        accept(guest, oldToken).andExpect(status().isOk());

        clearLink(owner, project).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/project/" + pid(project) + "/share-link").header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        String newToken = token(setLink(owner, project, "VIEWER"));

        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(role(project, guest)).isNull();
        accept(createUser("slother5"), oldToken).andExpect(status().isNotFound());
        accept(guest, newToken).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM project_share_link WHERE revoked_at IS NULL", Long.class)).isEqualTo(1);
    }

    @Test
    void onlyOwnerManagesLinksAndOwnerAcceptIsNoOp() throws Exception {
        User owner = createUser("slowner6");
        User editor = createUser("sleditor6");
        Project project = createProject(owner, "Shared", "private");
        String token = token(setLink(owner, project, "EDITOR"));
        accept(editor, token).andExpect(status().isOk());

        mockMvc.perform(put("/api/project/" + pid(project) + "/share-link")
                        .header("Authorization", bearer(editor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/project/" + pid(project) + "/share-link")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isBadRequest());
        accept(owner, token).andExpect(status().isOk());
        assertThat(role(project, owner)).isNull();
    }
}
