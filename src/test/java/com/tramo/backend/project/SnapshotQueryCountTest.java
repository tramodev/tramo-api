// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;



class SnapshotQueryCountTest extends AbstractIntegrationTest {

    private Project seedUnpublishedProject(User owner, String title, int trails, int itemsPerTrail) throws Exception {
        Project project = createProject(owner, title, "private", "A description", "java,testing");
        for (int t = 0; t < trails; t++) {
            long trailId = postForId(owner, "/api/project/" + pid(project) + "/trail", """
                    {"title":"Trail %d"}""".formatted(t));
            for (int i = 0; i < itemsPerTrail; i++) {
                postForId(owner, "/api/trail/" + trailId + "/item", """
                        {"title":"Item %d"}""".formatted(i));
            }
        }
        return project;
    }

    private void publish(User owner, Project project) throws Exception {
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isOk());
    }

    @Test
    void publishQueryCountDoesNotScaleWithItemCount() throws Exception {
        
        
        User smallOwner = createUser("qcsnapowner1");
        User largeOwner = createUser("qcsnapowner2");
        Project small = seedUnpublishedProject(smallOwner, "Snap Small", 2, 1);
        Project large = seedUnpublishedProject(largeOwner, "Snap Large", 2, 8);

        long smallCount = queryCount(() -> publish(smallOwner, small));
        long largeCount = queryCount(() -> publish(largeOwner, large));

        
        
        
        
        
        assertThat(largeCount).isCloseTo(smallCount, org.assertj.core.data.Offset.offset(2L));
    }

    @Test
    void versionsListQueryCountDoesNotScaleWithVersionCount() throws Exception {
        User owner = createUser("qcsnapowner2");
        Project project = seedUnpublishedProject(owner, "Snap Versions", 1, 1);
        publish(owner, project);

        long smallCount = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/versions")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 0; i < 6; i++) {
            mockMvc.perform(put("/api/project/" + pid(project))
                            .header("Authorization", bearer(owner))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("""
                                    {"visibility":"private"}"""))
                    .andExpect(status().isOk());
            publish(owner, project);
        }

        long largeCount = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/versions")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(largeCount).isEqualTo(smallCount);
    }
}
