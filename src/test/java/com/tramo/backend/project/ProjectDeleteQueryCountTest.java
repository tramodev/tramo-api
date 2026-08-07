package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectDeleteQueryCountTest extends AbstractIntegrationTest {

    private Project publishedWithEngagement(User owner, String title, int engagers) throws Exception {
        Project project = createProject(owner, title, "published", "A description", null);
        for (int i = 0; i < engagers; i++) {
            User fan = createUser(title.replaceAll("[^a-z]", "") + "fan" + i);
            mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/project/" + pid(project) + "/bookmark").header("Authorization", bearer(fan)))
                    .andExpect(status().isOk());
        }
        return project;
    }

    @Test
    void deleteQueryCountDoesNotScaleWithEngagementCount() throws Exception {
        User owner = createUser("pdqcowner");
        Project few = publishedWithEngagement(owner, "few", 1);
        Project many = publishedWithEngagement(owner, "many", 6);

        long small = queryCount(() -> mockMvc.perform(delete("/api/project/" + pid(few))
                .header("Authorization", bearer(owner))).andExpect(status().isNoContent()));

        long large = queryCount(() -> mockMvc.perform(delete("/api/project/" + pid(many))
                .header("Authorization", bearer(owner))).andExpect(status().isNoContent()));

        assertThat(large).isEqualTo(small);
    }
}
