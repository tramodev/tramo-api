package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.service.ProjectPublishService;
import com.tramo.backend.user.entity.User;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;




class PublicSnapshotSourcingTest extends AbstractIntegrationTest {

    @Autowired
    private ProjectPublishService publishService;

    private void setVisibility(User owner, Project project, String visibility) throws Exception {
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isOk());
    }

    private void renameProject(User owner, Project project, String newTitle) throws Exception {
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + newTitle + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void publicProjectPageShowsFrozenContentAfterLiveEdit() throws Exception {
        User owner = createUser("snapsrcowner1");
        Project project = createProject(owner, "Original Title", "private", "Original description", "tag");
        setVisibility(owner, project, "published");

        renameProject(owner, project, "Edited Title Live");

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Original Title"));
    }

    @Test
    void exploreFeedShowsFrozenContentAfterLiveEdit() throws Exception {
        User owner = createUser("snapsrcowner2");
        Project project = createProject(owner, "Explore Original", "private", "Original description", "tag");
        setVisibility(owner, project, "published");

        renameProject(owner, project, "Explore Edited Live");

        mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed[?(@.title=='Explore Original')]").exists())
                .andExpect(jsonPath("$.feed[?(@.title=='Explore Edited Live')]").doesNotExist());
    }

    @Test
    void unlistedProjectStaysLive() throws Exception {
        User owner = createUser("snapsrcowner3");
        Project project = createProject(owner, "Unlisted Original", "private", "Original description", "tag");
        setVisibility(owner, project, "unlisted");

        renameProject(owner, project, "Unlisted Edited Live");

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Unlisted Edited Live"));
    }

    @Test
    void backfillCreatesSnapshotForPreExistingPublishedProject() throws Exception {
        User owner = createUser("snapsrcowner4");
        
        
        Project project = createProject(owner, "Legacy Published", "published", "Legacy description", "tag");

        publishService.backfillMissingPublishSnapshots();

        renameProject(owner, project, "Legacy Edited After Backfill");

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Legacy Published"));
    }

    private long publishAndCaptureSnapshotId(User owner, Project project) throws Exception {
        setVisibility(owner, project, "published");
        String versionsJson = mockMvc.perform(get("/api/project/" + pid(project) + "/versions")
                        .header("Authorization", bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(versionsJson, "$[0].id")).longValue();
    }

    @Test
    void publicVersionHiddenAfterUnpublishToPrivate() throws Exception {
        User owner = createUser("snapsrcowner5");
        User otherUser = createUser("snapsrcowner5other");
        Project project = createProject(owner, "Will Be Unpublished", "private", "Original description", "tag");
        long snapshotId = publishAndCaptureSnapshotId(owner, project);

        setVisibility(owner, project, "private");

        mockMvc.perform(get("/api/public/project/" + pid(project) + "/versions/" + snapshotId))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/public/project/" + pid(project) + "/versions/" + snapshotId)
                        .header("Authorization", bearer(otherUser)))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicVersionStaysReadableWhenUnlisted() throws Exception {
        User owner = createUser("snapsrcowner6");
        Project project = createProject(owner, "Will Be Unlisted", "private", "Original description", "tag");
        long snapshotId = publishAndCaptureSnapshotId(owner, project);

        setVisibility(owner, project, "unlisted");

        mockMvc.perform(get("/api/public/project/" + pid(project) + "/versions/" + snapshotId))
                .andExpect(status().isOk());
    }

    @Test
    void publicVersionReadableWhilePublished() throws Exception {
        User owner = createUser("snapsrcowner7");
        Project project = createProject(owner, "Stays Published", "private", "Original description", "tag");
        long snapshotId = publishAndCaptureSnapshotId(owner, project);

        mockMvc.perform(get("/api/public/project/" + pid(project) + "/versions/" + snapshotId))
                .andExpect(status().isOk());
    }
}
