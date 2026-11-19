// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.service.ProjectTrashService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectTrashTest extends AbstractIntegrationTest {

    @Autowired
    ProjectRepository projectRepository;

    @Autowired
    ProjectTrashService trashService;

    private void trash(User user, Project project) throws Exception {
        mockMvc.perform(post("/api/project/" + pid(project) + "/trash").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());
    }

    @Test
    void trashedProjectLeavesEveryListAndUnpublishes() throws Exception {
        User owner = createUser("trashowner1");
        User viewer = createUser("trashviewer1");
        Project project = createProject(owner, "Trashed", "private", "Description", null);
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        addViewer(project, viewer);

        trash(owner, project);

        mockMvc.perform(get("/api/project").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/project").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/project/" + pid(project)).header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/project/" + pid(project)).header("Authorization", bearer(viewer)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/project/trash").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].trashedAt").isNotEmpty());
        mockMvc.perform(get("/api/project/trash").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.content[0].type").value("PROJECT_TRASHED"));
    }

    @Test
    void restoreBringsProjectBackPrivate() throws Exception {
        User owner = createUser("trashowner2");
        Project project = createProject(owner, "Restored", "private", "Description", null);
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        trash(owner, project);

        mockMvc.perform(post("/api/project/" + pid(project) + "/restore").header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/project/" + pid(project)).header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("private"))
                .andExpect(jsonPath("$.trashedAt").doesNotExist());
        mockMvc.perform(post("/api/project/" + pid(project) + "/restore").header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyOwnerCanTrashRestoreOrPurge() throws Exception {
        User owner = createUser("trashowner3");
        User intruder = createUser("trashintruder3");
        Project project = createProject(owner, "Mine", "private");
        addViewer(project, intruder);

        mockMvc.perform(post("/api/project/" + pid(project) + "/trash").header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());
        trash(owner, project);
        mockMvc.perform(post("/api/project/" + pid(project) + "/restore").header("Authorization", bearer(intruder)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/project/" + pid(project)).header("Authorization", bearer(intruder)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/project/" + pid(project)).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(projectRepository.findById(project.getId())).isEmpty();
    }

    @Test
    void purgeDeletesOnlyExpiredTrash() throws Exception {
        User owner = createUser("trashowner4");
        Project expired = createProject(owner, "Expired", "private");
        Project recent = createProject(owner, "Recent", "private");
        trash(owner, expired);
        trash(owner, recent);
        jdbcTemplate.update("UPDATE project SET trashed_at = now() - interval '31 days' WHERE id = ?", expired.getId());

        trashService.purgeExpired();

        assertThat(projectRepository.findById(expired.getId())).isEmpty();
        assertThat(projectRepository.findById(recent.getId())).isPresent();
    }
}
