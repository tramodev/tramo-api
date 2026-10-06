// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.trail.entity.ItemContent;
import com.tramo.backend.trail.service.ItemTextStatsBackfillRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ItemTextStatsTest extends AbstractIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private ItemTextStatsBackfillRunner backfill;

    @Test
    void countsSavedContentOnceAcrossTrailsAndProjectsAndTracksDeletion() throws Exception {
        var owner = createUser("statsowner");
        var project = createProject(owner, "Stats", "private");
        var other = createProject(owner, "Other", "private");
        long first = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"First\"}");
        long second = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Second\"}");
        long foreign = postForId(owner, "/api/project/" + pid(other) + "/trail", "{\"title\":\"Foreign\"}");
        long shared = postForId(owner, "/api/trail/" + first + "/item", "{\"title\":\"Shared\"}");
        long loose = postForId(owner, "/api/project/" + pid(project) + "/item", "{\"title\":\"Loose\"}");
        for (long trail : new long[]{second, foreign}) {
            mockMvc.perform(post("/api/trail/" + trail + "/item/" + shared).header("Authorization", bearer(owner)))
                    .andExpect(status().isNoContent());
        }
        String content = "{\"root\":{\"children\":[{\"text\":\"hello world\"}]}}";
        mockMvc.perform(put("/api/item/" + shared + "/content").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(java.util.Map.of("content", content))))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/project/" + pid(project) + "/text-stats").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.words").value(2))
                .andExpect(jsonPath("$.characters").value(11)).andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(get("/api/project/" + pid(other) + "/text-stats").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.words").value(2));
        var stranger = createUser("statsstranger");
        mockMvc.perform(get("/api/project/" + pid(project) + "/text-stats").header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/item/" + shared).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/project/" + pid(project) + "/text-stats").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.words").value(0))
                .andExpect(jsonPath("$.items[0].id").value(loose));
    }

    @Test
    void backfillsLegacyContentAndQueryCountDoesNotGrow() throws Exception {
        var owner = createUser("statsbackfill");
        var project = createProject(owner, "Backfill", "private");
        long item = postForId(owner, "/api/project/" + pid(project) + "/item", "{\"title\":\"Legacy\"}");
        jdbcTemplate.update("UPDATE item_content SET content = ?, word_count = NULL, character_count = NULL WHERE id = (SELECT content_id FROM item WHERE id = ?)",
                "{\"root\":{\"children\":[{\"text\":\"legacy text\"}]}}", item);
        backfill.run(null);
        long small = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/text-stats").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.words").value(2)));
        for (int i = 0; i < 6; i++) {
            postForId(owner, "/api/project/" + pid(project) + "/item", "{\"title\":\"More\"}");
        }
        long large = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/text-stats").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.words").value(2)));
        assertThat(large).isEqualTo(small);
    }

    @Test
    void matchesFrontendWhitespaceEquationsAndUtf16Characters() {
        var content = new ItemContent();
        content.setContent("{\"root\":{\"children\":[{\"text\":\" hello world﻿\"},{\"equation\":\"x + y\"},{\"text\":\"😀\"}]}}");
        assertThat(content.getWordCount()).isEqualTo(6);
        assertThat(content.getCharacterCount()).isEqualTo(20);
        var copy = new ItemContent();
        copy.setContent(content.getContent());
        assertThat(copy.getWordCount()).isEqualTo(content.getWordCount());
        content.setContent("invalid JSON");
        assertThat(content.getWordCount()).isZero();
        assertThat(content.getCharacterCount()).isZero();
    }
}
