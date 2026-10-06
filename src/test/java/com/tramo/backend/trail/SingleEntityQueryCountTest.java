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





class SingleEntityQueryCountTest extends AbstractIntegrationTest {

    private long createTrail(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"%s"}""".formatted(title));
    }

    private long createItem(User owner, long trailId, String title) throws Exception {
        return postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"%s"}""".formatted(title));
    }

    @Test
    void getTrailByIdQueryCountDoesNotScaleWithItemCount() throws Exception {
        User owner = createUser("seqcowner1");
        Project project = createProject(owner, "TrailRead", "private");
        long trailId = createTrail(owner, project, "T");
        createItem(owner, trailId, "Item 0");

        long small = queryCount(() -> mockMvc.perform(get("/api/trail/" + trailId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            createItem(owner, trailId, "Item " + i);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/trail/" + trailId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void getItemContentQueryCountDoesNotScaleWithSiblingItemCount() throws Exception {
        User owner = createUser("seqcowner2");
        Project project = createProject(owner, "ItemContentRead", "private");
        long trailId = createTrail(owner, project, "T");
        long targetItemId = createItem(owner, trailId, "Target");
        createItem(owner, trailId, "Sibling 0");

        long small = queryCount(() -> mockMvc.perform(get("/api/item/" + targetItemId + "/content")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            createItem(owner, trailId, "Sibling " + i);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/item/" + targetItemId + "/content")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void getProjectByIdQueryCountDoesNotScaleWithTrailCount() throws Exception {
        User owner = createUser("seqcowner3");
        Project project = createProject(owner, "ProjectRead", "private");
        long trailId = createTrail(owner, project, "Trail 0");
        createItem(owner, trailId, "Item 0");

        long small = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            long extraTrailId = createTrail(owner, project, "Trail " + i);
            createItem(owner, extraTrailId, "Item " + i);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }
}
