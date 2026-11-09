// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccessRequestTest extends AbstractIntegrationTest {

    private ResultActions request(User user, String projectId) throws Exception {
        return mockMvc.perform(post("/api/project/" + projectId + "/access-request").header("Authorization", bearer(user)));
    }

    private ResultActions approve(User user, Project project, String username, String role) throws Exception {
        return mockMvc.perform(post("/api/project/" + pid(project) + "/access-requests/" + username + "/approve")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"%s\"}".formatted(role)));
    }

    private long requests(Project project) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM project_access_request WHERE project_id = ?", Long.class, project.getId());
    }

    private long notifications(User owner) {
        return jdbcTemplate.queryForObject("SELECT coalesce(sum(count), 0) FROM notification WHERE recipient_id = ? AND type = 'ACCESS_REQUEST'",
                Long.class, owner.getId());
    }

    private String role(Project project, User user) {
        return jdbcTemplate.queryForList("SELECT role FROM project_member WHERE project_id = ? AND user_id = ?",
                String.class, project.getId(), user.getId()).stream().findFirst().orElse(null);
    }

    @Test
    void strangerRequestNotifiesTheOwnerOnceAndGrantsNothing() throws Exception {
        User owner = createUser("arowner1");
        User stranger = createUser("arstranger1");
        Project project = createProject(owner, "Private", "private");

        request(stranger, pid(project)).andExpect(status().isAccepted());
        request(stranger, pid(project)).andExpect(status().isAccepted());

        assertThat(requests(project)).isEqualTo(1);
        assertThat(notifications(owner)).isEqualTo(1);
        mockMvc.perform(get("/api/project/" + pid(project)).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/project/" + pid(project) + "/access-requests").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("arstranger1"));
    }

    @Test
    void missingProjectsBlockedUsersAndMembersGetTheSameAnswerWithoutSideEffects() throws Exception {
        User owner = createUser("arowner2");
        User blocked = createUser("arblocked2");
        User member = createUser("armember2");
        Project project = createProject(owner, "Private", "private");
        jdbcTemplate.update("INSERT INTO blocked_user(id, blocker_id, blocked_id) VALUES (999997, ?, ?)", owner.getId(), blocked.getId());
        mockMvc.perform(post("/api/project/" + pid(project) + "/members").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"armember2\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());

        request(blocked, pid(project)).andExpect(status().isAccepted()).andExpect(content().string(""));
        request(member, pid(project)).andExpect(status().isAccepted()).andExpect(content().string(""));
        request(owner, pid(project)).andExpect(status().isAccepted()).andExpect(content().string(""));
        Project other = createProject(owner, "Deleted", "private");
        String missing = pid(other);
        jdbcTemplate.update("DELETE FROM project WHERE id = ?", other.getId());
        request(blocked, missing).andExpect(status().isAccepted()).andExpect(content().string(""));
        request(blocked, "Qz7kP2").andExpect(status().isAccepted()).andExpect(content().string(""));

        assertThat(requests(project)).isZero();
        assertThat(notifications(owner)).isZero();
    }

    @Test
    void onlyTheOwnerListsApprovesAndDenies() throws Exception {
        User owner = createUser("arowner3");
        User editor = createUser("areditor3");
        User stranger = createUser("arstranger3");
        Project project = createProject(owner, "Private", "private");
        mockMvc.perform(post("/api/project/" + pid(project) + "/members").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"areditor3\",\"role\":\"EDITOR\"}"))
                .andExpect(status().isOk());
        request(stranger, pid(project)).andExpect(status().isAccepted());

        mockMvc.perform(get("/api/project/" + pid(project) + "/access-requests").header("Authorization", bearer(editor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/project/" + pid(project) + "/access-requests").header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        approve(editor, project, "arstranger3", "EDITOR").andExpect(status().isForbidden());
        approve(stranger, project, "arstranger3", "EDITOR").andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/project/" + pid(project) + "/access-requests/arstranger3").header("Authorization", bearer(editor)))
                .andExpect(status().isForbidden());
        assertThat(requests(project)).isEqualTo(1);
    }

    @Test
    void approveAddsTheMemberWithTheChosenRoleAndDenyDropsTheRequest() throws Exception {
        User owner = createUser("arowner4");
        User approved = createUser("arapproved4");
        User denied = createUser("ardenied4");
        Project project = createProject(owner, "Private", "private");
        request(approved, pid(project)).andExpect(status().isAccepted());
        request(denied, pid(project)).andExpect(status().isAccepted());

        approve(owner, project, "arapproved4", "OWNER").andExpect(status().isBadRequest());
        approve(owner, project, "ardenied4x", "VIEWER").andExpect(status().isNotFound());
        approve(owner, project, "arapproved4", "EDITOR").andExpect(status().isOk()).andExpect(jsonPath("$.role").value("EDITOR"));
        assertThat(role(project, approved)).isEqualTo("EDITOR");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification WHERE recipient_id = ? AND type = 'MEMBER_ADDED'",
                Long.class, approved.getId())).isEqualTo(1);

        mockMvc.perform(delete("/api/project/" + pid(project) + "/access-requests/ardenied4").header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(role(project, denied)).isNull();
        assertThat(requests(project)).isZero();
        approve(owner, project, "ardenied4", "VIEWER").andExpect(status().isNotFound());
    }
}
