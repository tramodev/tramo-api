// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EditorQueryCountTest extends AbstractIntegrationTest {

    @Test
    void editorBootstrapQueryCountDoesNotScaleWithTrailOrItemCount() throws Exception {
        User owner = createUser("eqcbootstrap");
        Project project = createProject(owner, "Editor", "private");
        long firstTrail = createTrail(owner, project, "First");
        createItem(owner, firstTrail, "First note");

        long small = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/editor")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contents.length()").value(1)));

        long secondTrail = createTrail(owner, project, "Second");
        for (int i = 0; i < 6; i++) createItem(owner, firstTrail, "First " + i);
        for (int i = 0; i < 6; i++) createItem(owner, secondTrail, "Second " + i);

        long large = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/editor")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contents.length()").value(7)));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void editorBootstrapSelectsRequestedTrailAndRejectsAnotherOwner() throws Exception {
        User owner = createUser("eqcselection");
        User other = createUser("eqcother");
        Project project = createProject(owner, "Editor selection", "private");
        long firstTrail = createTrail(owner, project, "First");
        long secondTrail = createTrail(owner, project, "Second");
        long itemId = createItem(owner, secondTrail, "Chosen");

        mockMvc.perform(get("/api/project/" + pid(project) + "/editor")
                        .param("noteId", String.valueOf(itemId))
                        .param("trailId", String.valueOf(secondTrail))
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.selectedTrailId").value(secondTrail))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.selectedItemId").value(itemId));

        mockMvc.perform(get("/api/project/" + pid(project) + "/editor")
                        .header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());
    }

    private long createTrail(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"%s"}""".formatted(title));
    }

    private long createItem(User owner, long trailId, String title) throws Exception {
        return postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"%s"}""".formatted(title));
    }

    @Test
    void getAllForTrailQueryCountDoesNotScaleWithItemCount() throws Exception {
        User owner = createUser("eqcowner1");
        Project project = createProject(owner, "TrailItems", "private");
        long trailId = createTrail(owner, project, "T");
        createItem(owner, trailId, "Item 0");

        long small = queryCount(() -> mockMvc.perform(get("/api/trail/" + trailId + "/item")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            createItem(owner, trailId, "Item " + i);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/trail/" + trailId + "/item")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void getContentsForTrailQueryCountDoesNotScaleWithItemCount() throws Exception {
        User owner = createUser("eqcowner6");
        Project project = createProject(owner, "TrailContents", "private");
        long trailId = createTrail(owner, project, "T");
        createItem(owner, trailId, "Item 0");

        long small = queryCount(() -> mockMvc.perform(get("/api/trail/" + trailId + "/content")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            createItem(owner, trailId, "Item " + i);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/trail/" + trailId + "/content")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void getItemsForProjectQueryCountDoesNotScaleWithItemCount() throws Exception {
        User owner = createUser("eqcowner2");
        Project project = createProject(owner, "ProjectItems", "private");
        long trailId = createTrail(owner, project, "T");
        createItem(owner, trailId, "Item 0");

        long small = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/item")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            createItem(owner, trailId, "Item " + i);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/item")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void getAllTrailsForProjectQueryCountDoesNotScaleWithTrailCount() throws Exception {
        User owner = createUser("eqcowner3");
        Project project = createProject(owner, "ProjectTrails", "private");
        createTrail(owner, project, "Trail 0");

        long small = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/trail")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            createTrail(owner, project, "Trail " + i);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/trail")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void mapPreviewQueryCountDoesNotScaleWithItemCount() throws Exception {
        User owner = createUser("eqcmapowner");
        Project project = createProject(owner, "MapPreviews", "private");
        long trailId = createTrail(owner, project, "T");
        createItem(owner, trailId, "Item 0");

        long small = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/map-preview")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            createItem(owner, trailId, "Item " + i);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/map-preview")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void itemSearchQueryCountDoesNotScaleWithItemCount() throws Exception {
        User owner = createUser("eqcowner5");
        Project project = createProject(owner, "SearchItems", "private");
        long trailId = createTrail(owner, project, "T");
        createItem(owner, trailId, "Item 0");

        long small = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/item/search?q=cuarzo")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            createItem(owner, trailId, "Item " + i);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/item/search?q=cuarzo")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

}
