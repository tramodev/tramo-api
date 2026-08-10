package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BannedUserVisibilityTest extends AbstractIntegrationTest {

    @Test
    void banningOwnerHidesProjectFromExploreAndDirectLinkButNotFromOwner() throws Exception {
        User owner = createUser("bannedauthor");
        User stranger = createUser("bannedviewer");
        Project project = createProject(owner, "Spam post", "published", "desc", null);

        mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(1));
        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk());

        String ownerTokenFromBeforeTheBan = bearer(owner);

        owner.setBanned(true);
        userRepository.save(owner);

        mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(0));
        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/project/" + pid(project)).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/public/project/" + pid(project)).header("Authorization", ownerTokenFromBeforeTheBan))
                .andExpect(status().isOk());

        assertThat(projectRepository.findById(project.getId()).orElseThrow().getVisibility())
                .isEqualTo(ProjectVisibility.PUBLISHED);

        owner.setBanned(false);
        userRepository.save(owner);

        mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.length()").value(1))
                .andExpect(jsonPath("$.feed[0].title").value("Spam post"));
        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk());
    }
}
