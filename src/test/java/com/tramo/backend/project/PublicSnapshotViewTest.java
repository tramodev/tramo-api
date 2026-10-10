// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicSnapshotViewTest extends AbstractIntegrationTest {

    private long createTrail(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"%s"}""".formatted(title));
    }

    private long createItem(User owner, long trailId, String title) throws Exception {
        return postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"%s"}""".formatted(title));
    }

    private void setContent(User owner, long itemId, String content) throws Exception {
        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isNoContent());
    }

    private void publish(User owner, Project project) throws Exception {
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
    }

    private Project publishedProjectWithContent(User owner, String title) throws Exception {
        Project project = createProject(owner, title, "private", "A description", "java");
        long trailId = createTrail(owner, project, "Chapter one");
        long itemA = createItem(owner, trailId, "A");
        long itemB = createItem(owner, trailId, "B");
        setContent(owner, itemA, "body of A");
        publish(owner, project);
        return project;
    }

    @Test
    void publicViewOfPublishedProjectIsServedFromTheSnapshot() throws Exception {
        User owner = createUser("psvowner1");
        Project project = publishedProjectWithContent(owner, "Snapshotted");

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Snapshotted"))
                .andExpect(jsonPath("$.ownerUsername").value("psvowner1"))
                .andExpect(jsonPath("$.trails.length()").value(1))
                .andExpect(jsonPath("$.trails[0].title").value("Chapter one"))
                .andExpect(jsonPath("$.trails[0].items.length()").value(2))
                .andExpect(jsonPath("$.trails[0].items[0].title").value("A"))
                .andExpect(jsonPath("$.trails[0].items[0].content").value("body of A"));
    }

    @Test
    void snapshotViewKeepsShowingPublishedContentAfterALiveEdit() throws Exception {
        User owner = createUser("psvowner4");
        Project project = createProject(owner, "Frozen", "private", "A description", null);
        long trailId = createTrail(owner, project, "T");
        long itemId = createItem(owner, trailId, "Item");
        setContent(owner, itemId, "published body");
        publish(owner, project);
        setContent(owner, itemId, "draft body");

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trails[0].items[0].content").value("published body"));
    }

    @Test
    void anonymousViewsAreCountedOncePerAnonId() throws Exception {
        User owner = createUser("psvowner5");
        Project project = publishedProjectWithContent(owner, "Counted");

        mockMvc.perform(get("/api/public/project/" + pid(project)).header("X-Anon-Id", "anon-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(1));

        mockMvc.perform(get("/api/public/project/" + pid(project)).header("X-Anon-Id", "anon-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(1));

        mockMvc.perform(get("/api/public/project/" + pid(project)).header("X-Anon-Id", "anon-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(2));
    }

    @Test
    void viewsWithoutAnyViewerKeyAreNotCounted() throws Exception {
        User owner = createUser("psvowner6");
        Project project = publishedProjectWithContent(owner, "Uncounted");

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(0));

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(0));
    }

    @Test
    void publicViewExposesForkSourceAndPermissions() throws Exception {
        User owner = createUser("psvowner7");
        User forker = createUser("psvforker7");
        Project source = publishedProjectWithContent(owner, "Source");
        String forkId = postForProjectId(forker, "/api/project/" + pid(source) + "/fork", "");
        Project fork = projectRepository.findById(projectIdCodec.decode(forkId)).orElseThrow();
        mockMvc.perform(post("/api/project/" + pid(fork) + "/publish").header("Authorization", bearer(forker)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/project/" + forkId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forkedFromProjectId").value(pid(source)))
                .andExpect(jsonPath("$.forkedFromOwnerUsername").value("psvowner7"))
                .andExpect(jsonPath("$.canFork").value(true))
                .andExpect(jsonPath("$.canComment").value(false));
    }

    @Test
    void unpublishedProjectIsNotPubliclyVisible() throws Exception {
        User owner = createUser("psvowner8");
        Project project = createProject(owner, "Hidden", "private", "A description", null);

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isNotFound());
    }
}
