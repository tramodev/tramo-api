// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project;

import com.jayway.jsonpath.JsonPath;
import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;






class PublicProjectContentTest extends AbstractIntegrationTest {

    private long createTrail(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"%s"}""".formatted(title));
    }

    private long createItem(User owner, long trailId, String title) throws Exception {
        return postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"%s"}""".formatted(title));
    }

    @Test
    void publicProjectExposesThumbnailImageUrlWhenDedicatedButNotForGraphOrNone() throws Exception {
        User owner = createUser("publicthumbowner");
        Project project = createProject(owner, "Thumbed", "published");

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thumbnailImageUrl").value(nullValue()));

        String thumbnail = "https://test-bucket.example.com/thumbnail/" + owner.getId() + "/thumb.png";
        mockMvc.perform(put("/api/project/" + pid(project) + "/thumbnail")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEDICATED","imageUrl":"%s"}""".formatted(thumbnail)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thumbnailImageUrl").value(thumbnail));
    }

}
