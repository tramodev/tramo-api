package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.repository.ProjectVoteRepository;
import com.tramo.backend.project.service.ProjectService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeaturedProjectTest extends AbstractIntegrationTest {

    @Autowired
    ProjectService projectService;

    @Autowired
    ProjectVoteRepository projectVoteRepository;

    private void vote(User voter, Project project, String ip, String deviceId) throws Exception {
        var request = post("/api/project/" + pid(project) + "/vote")
                .header("Authorization", bearer(voter))
                .header("X-Forwarded-For", ip);
        if (deviceId != null) {
            request.header("X-Anon-Id", deviceId);
        }
        mockMvc.perform(request).andExpect(status().isOk());
    }

    @Test
    void voteCapturesIpAndDevice() throws Exception {
        User owner = createUser("owner");
        User voter = createUser("voter");
        Project project = createProject(owner, "Tracked", "published");

        vote(voter, project, "203.0.113.7", "device-abc");

        var saved = projectVoteRepository.findByProjectIdAndUserId(project.getId(), voter.getId()).orElseThrow();
        assertThat(saved.getVoterIp()).isEqualTo("203.0.113.7");
        assertThat(saved.getDeviceId()).isEqualTo("device-abc");
    }

    @Test
    void sameIpFarmLosesToFewerDistinctVoters() throws Exception {
        User farmer = createUser("farmer");
        User honest = createUser("honest");
        Project farmed = createProject(farmer, "Farmed", "published");
        Project organic = createProject(honest, "Organic", "published");

        for (int i = 0; i < 5; i++) {
            vote(createUser("sock" + i), farmed, "198.51.100.1", "farm-device-" + i);
        }
        vote(createUser("real1"), organic, "203.0.113.1", "device-1");
        vote(createUser("real2"), organic, "203.0.113.2", "device-2");

        projectService.refreshFeaturedProject();

        assertThat(projectRepository.findById(organic.getId()).orElseThrow().isFeatured()).isTrue();
        assertThat(projectRepository.findById(farmed.getId()).orElseThrow().isFeatured()).isFalse();
    }

    @Test
    void sameDeviceAcrossIpsCollapsesToOneVote() throws Exception {
        User farmer = createUser("farmer");
        User honest = createUser("honest");
        Project farmed = createProject(farmer, "Farmed", "published");
        Project organic = createProject(honest, "Organic", "published");

        for (int i = 0; i < 5; i++) {
            vote(createUser("sock" + i), farmed, "198.51.100." + i, "shared-device");
        }
        vote(createUser("real1"), organic, "203.0.113.1", "device-1");
        vote(createUser("real2"), organic, "203.0.113.2", "device-2");

        projectService.refreshFeaturedProject();

        assertThat(projectRepository.findById(organic.getId()).orElseThrow().isFeatured()).isTrue();
    }

    @Test
    void legacyVotesWithoutMetadataStillCount() throws Exception {
        User owner = createUser("owner");
        Project project = createProject(owner, "Legacy", "published");
        for (int i = 0; i < 3; i++) {
            User voter = createUser("old" + i);
            mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(voter)))
                    .andExpect(status().isOk());
        }
        jdbcTemplate.update("UPDATE project_vote SET voter_ip = NULL, device_id = NULL");

        projectService.refreshFeaturedProject();

        assertThat(projectRepository.findById(project.getId()).orElseThrow().isFeatured()).isTrue();
    }

    @Test
    void rawVoteCountDisplayUnaffectedByDiscounting() throws Exception {
        User farmer = createUser("farmer");
        Project farmed = createProject(farmer, "Farmed", "published");
        for (int i = 0; i < 5; i++) {
            vote(createUser("sock" + i), farmed, "198.51.100.1", "farm-device");
        }

        mockMvc.perform(get("/api/public/project/" + pid(farmed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voteCount").value(5));
    }
}
