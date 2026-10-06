// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.tag;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.tag.entity.Tag;
import com.tramo.backend.tag.repository.TagRepository;
import com.tramo.backend.tag.service.TagSeeder;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TagSystemTest extends AbstractIntegrationTest {

    @Autowired
    TagRepository tagRepository;

    @Autowired
    TagSeeder tagSeeder;

    
    @BeforeEach
    void reseedTags() {
        tagSeeder.run(null);
    }

    private void tagProject(User owner, String title, String tag) throws Exception {
        mockMvc.perform(post("/api/project")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"tags\":[\"" + tag + "\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void userCreatedTagHiddenUntilUsageCrossesThreshold() throws Exception {
        User owner = createUser("hiddengem-owner");
        tagProject(owner, "P1", "hiddengem");
        tagProject(owner, "P2", "hiddengem");

        mockMvc.perform(get("/api/tags/autocomplete").param("q", "hiddengem").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        Tag tag = tagRepository.findByName("hiddengem").orElseThrow();
        assertThat(tag.getUsageCount()).isEqualTo(2);
        assertThat(tag.isOfficial()).isFalse();

        tagProject(owner, "P3", "hiddengem");

        mockMvc.perform(get("/api/tags/autocomplete").param("q", "hiddengem").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("hiddengem"));
    }

    @Test
    void officialSeedTagIsAlwaysVisible() throws Exception {
        User owner = createUser("seed-checker");

        mockMvc.perform(get("/api/tags/autocomplete").param("q", "javascript").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("javascript"))
                .andExpect(jsonPath("$[0].official").value(true));
    }

    @Test
    void removingTagOnUpdateDecrementsUsageCount() throws Exception {
        User owner = createUser("decrementer");
        String id = postForProjectId(owner, "/api/project", """
                {"title":"Tagged","tags":["decrement-me"]}""");
        assertThat(tagRepository.findByName("decrement-me").orElseThrow().getUsageCount()).isEqualTo(1);

        mockMvc.perform(put("/api/project/" + id)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tags":[]}"""))
                .andExpect(status().isOk());

        assertThat(tagRepository.findByName("decrement-me").orElseThrow().getUsageCount()).isEqualTo(0);
    }

    @Test
    void differentCasingAndSpacingResolveToSameTagRow() throws Exception {
        User owner = createUser("dedup-owner");
        tagProject(owner, "P1", "React");
        tagProject(owner, "P2", "react");

        assertThat(tagRepository.findAll().stream().filter(t -> t.getName().equals("react")).count()).isEqualTo(1);
        assertThat(tagRepository.findByName("react").orElseThrow().getUsageCount()).isEqualTo(2);
    }
}
