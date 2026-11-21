package com.tramo.backend.trail;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.trail.dto.TrailRequestDTO;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.trail.service.TrailService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TrailTitleSuffixTest extends AbstractIntegrationTest {
    @Autowired TrailRepository trails;
    @Autowired TrailService service;

    TrailRequestDTO titled(String title) {
        TrailRequestDTO request = new TrailRequestDTO();
        request.setTitle(title);
        return request;
    }

    @Test
    void suffixesDuplicateTitlesOnCreateAndRename() throws Exception {
        User owner = createUser("trailsuffix");
        Project project = createProject(owner, "Suffix", "private");
        String path = "/api/project/" + pid(project) + "/trail";
        postForId(owner, path, "{\"title\":\"QA Trail 2\"}");
        postForId(owner, path, "{\"title\":\"QA Trail 2\"}");
        long other = postForId(owner, path, "{\"title\":\"Other\"}");
        mockMvc.perform(put("/api/trail/" + other).header("Authorization", bearer(owner))
                .contentType("application/json").content("{\"title\":\"QA Trail 2\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("QA Trail 2 (3)"));
        mockMvc.perform(put("/api/trail/" + other).header("Authorization", bearer(owner))
                .contentType("application/json").content("{\"title\":\"QA Trail 2 (3)\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("QA Trail 2 (3)"));
        assertThat(trails.findByProjectId(project.getId())).extracting(Trail::getTitle)
                .containsExactly("QA Trail 2", "QA Trail 2 (2)", "QA Trail 2 (3)");
    }

    @Test
    void concurrentCreatesWithSameTitleGetDistinctTitles() throws Exception {
        User owner = createUser("trailsuffixrace");
        Project project = createProject(owner, "Race", "private");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.create(project.getId(), titled("Same"), owner));
            var second = pool.submit(() -> service.create(project.getId(), titled("Same"), owner));
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(trails.findByProjectId(project.getId())).extracting(Trail::getTitle).containsExactlyInAnyOrder("Same", "Same (2)");
    }

    @Test
    void keepsSuffixedTitleWithinColumnLimit() throws Exception {
        User owner = createUser("trailsuffixlong");
        Project project = createProject(owner, "Long", "private");
        String title = "x".repeat(255);
        service.create(project.getId(), titled(title), owner);
        assertThat(service.create(project.getId(), titled(title), owner).getTitle()).hasSize(255).endsWith(" (2)");
    }
}
