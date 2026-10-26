package com.tramo.backend.trail;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.trail.service.TrailService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectTrailReorderTest extends AbstractIntegrationTest {
    @Autowired TrailRepository trails;
    @Autowired TrailService service;
    private final ObjectMapper mapper = new ObjectMapper();
    record Fixture(User owner, Project project, long first, long second, long third, long note) {}

    Fixture fixture() throws Exception {
        User owner = createUser("trailorder");
        Project project = createProject(owner, "Ordered", "unlisted", "A description", "tag");
        long first = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"First\"}");
        long second = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Second\"}");
        long third = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Third\"}");
        long note = postForId(owner, "/api/trail/" + third + "/item", "{\"title\":\"Note\"}");
        return new Fixture(owner, project, first, second, third, note);
    }

    void reorder(Fixture f, User user, List<Long> ids, int status) throws Exception {
        mockMvc.perform(put("/api/project/" + pid(f.project) + "/trail/order").header("Authorization", bearer(user))
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("trailIds", ids))))
                .andExpect(status().is(status));
    }

    @Test
    void persistsOrderInEditorPublicReaderAndStartWithoutChangingMemberships() throws Exception {
        Fixture f = fixture();
        reorder(f, f.owner, List.of(f.third, f.first, f.second), 204);
        mockMvc.perform(get("/api/project/" + pid(f.project) + "/trail").header("Authorization", bearer(f.owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].id", contains((int) f.third, (int) f.first, (int) f.second)));
        mockMvc.perform(get("/api/project/" + pid(f.project) + "/editor").header("Authorization", bearer(f.owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trails[*].id", contains((int) f.third, (int) f.first, (int) f.second)))
                .andExpect(jsonPath("$.selectedTrailId").value(f.third));
        mockMvc.perform(get("/api/public/project/" + pid(f.project)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trails[*].id", contains((int) f.third, (int) f.first, (int) f.second)));
        mockMvc.perform(post("/api/project/" + pid(f.project) + "/start").header("Authorization", bearer(f.owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trailId").value(f.third));
        assertThat(jdbcTemplate.queryForList("SELECT item_id FROM trail_item WHERE trail_id = ?", Long.class, f.third)).containsExactly(f.note);
        assertThat(jdbcTemplate.queryForList("SELECT order_index FROM trail WHERE project_id = ? ORDER BY order_index", Integer.class, f.project.getId())).containsExactly(0, 1, 2);
    }

    @Test
    void rejectsMissingDuplicateForeignAndInvalidIdsWithoutChangingOrder() throws Exception {
        Fixture f = fixture();
        Project other = createProject(f.owner, "Other", "private");
        long foreign = postForId(f.owner, "/api/project/" + pid(other) + "/trail", "{\"title\":\"Foreign\"}");
        for (var ids : List.of(List.of(f.first), List.of(f.first, f.first, f.third), List.of(f.first, f.second, foreign), List.of(f.first, f.second, -1L))) {
            reorder(f, f.owner, ids, 400);
        }
        mockMvc.perform(put("/api/project/" + pid(f.project) + "/trail/order").header("Authorization", bearer(f.owner))
                .contentType("application/json").content("{\"trailIds\":[null]}"))
                .andExpect(status().isBadRequest());
        assertThat(trails.findByProjectId(f.project.getId())).extracting(Trail::getId).containsExactly(f.first, f.second, f.third);
    }

    @Test
    void deniesReorderingAnotherOwnersProject() throws Exception {
        Fixture f = fixture();
        reorder(f, createUser("trailstranger"), List.of(f.third, f.second, f.first), 404);
        assertThat(trails.findByProjectId(f.project.getId())).extracting(Trail::getId).containsExactly(f.first, f.second, f.third);
    }

    @Test
    void newTrailsAppendAfterReorderingAndDeleting() throws Exception {
        Fixture f = fixture();
        reorder(f, f.owner, List.of(f.third, f.first, f.second), 204);
        mockMvc.perform(delete("/api/trail/" + f.first).header("Authorization", bearer(f.owner))).andExpect(status().isNoContent());
        long added = postForId(f.owner, "/api/project/" + pid(f.project) + "/trail", "{\"title\":\"New\"}");
        assertThat(trails.findByProjectId(f.project.getId())).extracting(Trail::getId).containsExactly(f.third, f.second, added);
    }

    @Test
    void liveAndPublishedForksKeepAuthorOrderAndPublishedOrderStaysFrozen() throws Exception {
        Fixture f = fixture();
        User reader = createUser("trailreader");
        reorder(f, f.owner, List.of(f.third, f.first, f.second), 204);
        String liveFork = postForProjectId(reader, "/api/project/" + pid(f.project) + "/fork", "");
        assertThat(trails.findByProjectId(projectIdCodec.decode(liveFork))).extracting(Trail::getTitle).containsExactly("Third", "First", "Second");
        mockMvc.perform(post("/api/project/" + pid(f.project) + "/publish").header("Authorization", bearer(f.owner))).andExpect(status().isOk());
        reorder(f, f.owner, List.of(f.first, f.second, f.third), 204);
        mockMvc.perform(get("/api/public/project/" + pid(f.project))).andExpect(status().isOk())
                .andExpect(jsonPath("$.trails[*].title", contains("Third", "First", "Second")));
        String frozenFork = postForProjectId(reader, "/api/project/" + pid(f.project) + "/fork", "");
        assertThat(trails.findByProjectId(projectIdCodec.decode(frozenFork))).extracting(Trail::getTitle).containsExactly("Third", "First", "Second");
    }

    @Test
    void exportKeepsTrailOrder() throws Exception {
        Fixture f = fixture();
        reorder(f, f.owner, List.of(f.third, f.first, f.second), 204);
        byte[] bytes = mockMvc.perform(get("/api/project/" + pid(f.project) + "/export").header("Authorization", bearer(f.owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("project.json")) {
                    var paths = mapper.readTree(zip.readAllBytes()).path("trails");
                    assertThat(paths.get(0).path("id").asLong()).isEqualTo(f.third);
                    assertThat(paths.get(1).path("id").asLong()).isEqualTo(f.first);
                    assertThat(paths.get(2).path("id").asLong()).isEqualTo(f.second);
                    return;
                }
            }
        }
        fail("Missing project.json");
    }

    @Test
    void concurrentReordersLeaveOneCompleteOrder() throws Exception {
        Fixture f = fixture();
        var a = List.of(f.third, f.first, f.second);
        var b = List.of(f.second, f.third, f.first);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.reorder(f.project.getId(), a, f.owner));
            var second = pool.submit(() -> service.reorder(f.project.getId(), b, f.owner));
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(trails.findByProjectId(f.project.getId()).stream().map(Trail::getId).toList()).isIn(a, b);
        assertThat(jdbcTemplate.queryForList("SELECT order_index FROM trail WHERE project_id = ? ORDER BY order_index", Integer.class, f.project.getId())).containsExactly(0, 1, 2);
    }
}
