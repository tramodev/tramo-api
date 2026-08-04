package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectFeedOrderingTest extends AbstractIntegrationTest {

    @Autowired
    private ProjectRepository projectRepository;

    private void setVisibility(User owner, Project project, String visibility) throws Exception {
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isOk());
    }

    private void editDescription(User owner, Project project, String newDescription) throws Exception {
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"" + newDescription + "\"}"))
                .andExpect(status().isOk());
    }

    private Date lastPublishedDate(Project project) {
        return projectRepository.findById(project.getId()).orElseThrow().getLastPublishedDate();
    }

    private Date modifiedDate(Project project) {
        return projectRepository.findById(project.getId()).orElseThrow().getModifiedDate();
    }

    @Test
    void unpublishThenRepublishAdvancesLastPublishedDate() throws Exception {
        User owner = createUser("feedorderowner2");
        Project project = createProject(owner, "Feed Order Republish", "private", "Original description", "tag");
        setVisibility(owner, project, "published");
        Date firstLastPublished = lastPublishedDate(project);

        setVisibility(owner, project, "private");
        setVisibility(owner, project, "published");
        Date secondLastPublished = lastPublishedDate(project);

        assertThat(secondLastPublished).isAfter(firstLastPublished);
    }

    @Test
    void republishingAlreadyPublishedProjectDoesNotBumpLastPublishedDate() throws Exception {
        User owner = createUser("feedorderowner3");
        Project project = createProject(owner, "Feed Order Publish Twice", "private", "Original description", "tag");
        setVisibility(owner, project, "published");
        Date lastPublishedAfterInitialPublish = lastPublishedDate(project);

        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        Date lastPublishedAfterFirstRepublishCall = lastPublishedDate(project);

        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        Date lastPublishedAfterSecondRepublishCall = lastPublishedDate(project);

        assertThat(lastPublishedAfterFirstRepublishCall).isEqualTo(lastPublishedAfterInitialPublish);
        assertThat(lastPublishedAfterSecondRepublishCall).isEqualTo(lastPublishedAfterInitialPublish);
    }

    @Test
    void editingProjectStillAdvancesModifiedDate() throws Exception {
        User owner = createUser("feedorderowner4");
        Project project = createProject(owner, "Feed Order Modified", "private", "Original description", "tag");
        Date modifiedBefore = modifiedDate(project);

        editDescription(owner, project, "Original description edited");
        Date modifiedAfter = modifiedDate(project);

        assertThat(modifiedAfter).isAfter(modifiedBefore);
    }

}
