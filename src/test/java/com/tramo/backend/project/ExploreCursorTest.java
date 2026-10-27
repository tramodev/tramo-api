package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExploreCursorTest extends AbstractIntegrationTest {
    @Test
    void continuationSurvivesPublishingUnpublishingAndDeletingItsAnchorInEverySort() throws Exception {
        for (String sort : List.of("recent", "hot", "following")) {
            String query = "cursor" + sort;
            var owner = createUser(query + "owner"); var viewer = createUser(query + "viewer");
            mockMvc.perform(post("/api/users/" + owner.getUsername() + "/follow").header("Authorization", bearer(viewer))).andExpect(status().isOk());
            List<Project> projects = new ArrayList<>();
            for (int index = 0; index < 7; index++) {
                Project project = createProject(owner, query + " " + index, "published", "Description", null);
                jdbcTemplate.update("UPDATE project SET last_published_date = ? WHERE id = ?", Timestamp.from(Instant.parse("2026-01-01T00:00:00.123456Z")), project.getId());
                projects.add(project);
            }
            String first = mockMvc.perform(get("/api/public/explore").param("q", query).param("sort", sort).param("size", "2").header("Authorization", bearer(viewer)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> initial = JsonPath.read(first, "$.feed[*].id");
            assertThat(initial).containsExactly(pid(projects.get(6)), pid(projects.get(5)));
            String cursor = JsonPath.read(first, "$.nextCursor");
            var newer = createProject(owner, query + " newest", "published", "Description", null);
            jdbcTemplate.update("UPDATE project SET last_published_date = ? WHERE id = ?", Timestamp.from(Instant.parse("2026-02-01T00:00:00Z")), newer.getId());
            jdbcTemplate.update("UPDATE project SET visibility = 'private' WHERE id IN (?, ?)", projects.get(6).getId(), projects.get(4).getId());
            mockMvc.perform(delete("/api/project/" + pid(projects.get(5))).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
            List<String> remaining = new ArrayList<>();
            for (int page = 0; cursor != null && page < 10; page++) {
                String body = mockMvc.perform(get("/api/public/explore").param("q", query).param("sort", sort).param("size", "2").param("cursor", cursor).header("Authorization", bearer(viewer)))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                remaining.addAll(JsonPath.<List<String>>read(body, "$.feed[*].id"));
                cursor = JsonPath.read(body, "$.nextCursor");
            }
            assertThat(cursor).isNull();
            assertThat(remaining).containsExactly(pid(projects.get(3)), pid(projects.get(2)), pid(projects.get(1)), pid(projects.get(0)));
            assertThat(new HashSet<>(remaining)).hasSize(remaining.size());
            assertThat(remaining).doesNotContainAnyElementsOf(initial);
        }
    }

    @Test
    void hotCursorContinuesAcrossDifferentVoteCounts() throws Exception {
        var owner = createUser("hotcursorowner");
        var voter = createUser("hotcursorvoter");
        var popular = createProject(owner, "Hot cursor popular", "published", "Description", null);
        var newer = createProject(owner, "Hot cursor newer", "published", "Description", null);
        mockMvc.perform(post("/api/project/" + pid(popular) + "/vote").header("Authorization", bearer(voter))).andExpect(status().isOk());
        String first = mockMvc.perform(get("/api/public/explore").param("sort", "hot").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.feed[0].id").value(pid(popular))).andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(first, "$.nextCursor");
        mockMvc.perform(get("/api/public/explore").param("sort", "hot").param("size", "1").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.feed[0].id").value(pid(newer))).andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void invalidSortAndCursorReturnBadRequest() throws Exception {
        for (String query : List.of("sort=", "sort=invalid", "cursor=invalid", "cursor=", "cursor=" + "x".repeat(300))) {
            mockMvc.perform(get("/api/public/explore?" + query)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        var owner = createUser("cursormismatch");
        for (int index = 0; index < 3; index++) createProject(owner, "Mismatch " + index, "published", "Description", null);
        String body = mockMvc.perform(get("/api/public/explore").param("size", "1")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(body, "$.nextCursor");
        for (String query : List.of("sort=hot", "page=1")) {
            mockMvc.perform(get("/api/public/explore?" + query).param("cursor", cursor)).andExpect(status().isBadRequest());
        }
    }
}
