package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.repository.ProjectSnapshotRepository;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SnapshotTest extends AbstractIntegrationTest {

    @Autowired
    private ProjectSnapshotRepository projectSnapshotRepository;

    private Project seedProject(User owner, String title) throws Exception {
        return createProject(owner, title, "private", "A description", "java,testing");
    }

    private void setVisibility(User owner, Project project, String visibility) throws Exception {
        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void publishingTwiceCreatesIncrementingVersions() throws Exception {
        User owner = createUser("snapowner1");
        Project project = seedProject(owner, "Versioned");

        setVisibility(owner, project, "published");
        setVisibility(owner, project, "private");
        setVisibility(owner, project, "published");

        mockMvc.perform(get("/api/project/" + pid(project) + "/versions").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].version").value(1));
    }

    @Test
    void versionDetailContainsFrozenTrailAndItemContent() throws Exception {
        User owner = createUser("snapowner2");
        Project project = seedProject(owner, "Detailed");
        long trailId = postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"Trail 0"}""");
        long itemId = postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"Item 0"}""");
        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Hello frozen world"}"""))
                .andExpect(status().isNoContent());

        setVisibility(owner, project, "published");

        String versionsJson = mockMvc.perform(get("/api/project/" + pid(project) + "/versions")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long snapshotId = ((Number) com.jayway.jsonpath.JsonPath.read(versionsJson, "$[0].id")).longValue();

        mockMvc.perform(get("/api/project/" + pid(project) + "/versions/" + snapshotId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.content.trails[0].title").value("Trail 0"))
                .andExpect(jsonPath("$.content.trails[0].version").value(2))
                .andExpect(jsonPath("$.content.trails[0].items[0].content").value("Hello frozen world"));

        
        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Edited after publish"}"""))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/project/" + pid(project) + "/versions/" + snapshotId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.trails[0].items[0].content").value("Hello frozen world"));
    }

    @Test
    void forkingDoesNotCreateAStraySnapshot() throws Exception {
        User owner = createUser("snapowner3");
        User forker = createUser("snapforker3");
        Project project = seedProject(owner, "Forkable");
        setVisibility(owner, project, "published");

        postForProjectId(forker, "/api/project/" + pid(project) + "/fork", "");

        assertThat(projectSnapshotRepository.count()).isEqualTo(1);
        mockMvc.perform(get("/api/project/" + pid(project) + "/versions").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].version").value(1));
    }

    @Test
    void forkCopiesPublishedContentNotUnpublishedLiveEdits() throws Exception {
        User owner = createUser("snapowner9");
        User forker = createUser("snapforker9");
        Project project = seedProject(owner, "Draftable");
        long trailId = postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"Trail 0"}""");
        long itemId = postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"Item 0"}""");
        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Published content"}"""))
                .andExpect(status().isNoContent());

        setVisibility(owner, project, "published");

        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Unpublished draft edit"}"""))
                .andExpect(status().isNoContent());

        String forkId = postForProjectId(forker, "/api/project/" + pid(project) + "/fork", "");
        long forkedItemId = firstItemIdOfFork(forkId, forker);

        mockMvc.perform(get("/api/item/" + forkedItemId + "/content").header("Authorization", bearer(forker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Published content"));
    }

    private long firstItemIdOfFork(String forkId, User forker) throws Exception {
        String trailsJson = mockMvc.perform(get("/api/project/" + forkId + "/trail").header("Authorization", bearer(forker)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long trailId = ((Number) com.jayway.jsonpath.JsonPath.read(trailsJson, "$[0].id")).longValue();

        String itemsJson = mockMvc.perform(get("/api/trail/" + trailId + "/item").header("Authorization", bearer(forker)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(itemsJson, "$[0].id")).longValue();
    }

    @Test
    void forkOfNeverPublishedUnlistedProjectUsesLiveContent() throws Exception {
        User owner = createUser("snapowner10");
        User forker = createUser("snapforker10");
        Project project = seedProject(owner, "UnlistedOnly");
        long trailId = postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"Trail 0"}""");
        long itemId = postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"Item 0"}""");
        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Never published content"}"""))
                .andExpect(status().isNoContent());
        setVisibility(owner, project, "unlisted");

        String forkId = postForProjectId(forker, "/api/project/" + pid(project) + "/fork", "");
        long forkedItemId = firstItemIdOfFork(forkId, forker);

        mockMvc.perform(get("/api/item/" + forkedItemId + "/content").header("Authorization", bearer(forker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Never published content"));
    }

    @Test
    void versionHistoryIsOwnerOnly() throws Exception {
        User owner = createUser("snapowner4");
        User stranger = createUser("snapstranger4");
        Project project = seedProject(owner, "Private History");
        setVisibility(owner, project, "published");

        mockMvc.perform(get("/api/project/" + pid(project) + "/versions").header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
    }

    @Test
    void republishWhileAlreadyPublishedCreatesNewVersion() throws Exception {
        User owner = createUser("snapowner5");
        Project project = seedProject(owner, "Republished");

        setVisibility(owner, project, "published");
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("published"));
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/project/" + pid(project) + "/versions").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$[0].version").value(3))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[2].version").value(1));
    }

    @Test
    void votesAndCommentsSurviveRepublish() throws Exception {
        User owner = createUser("snapowner6");
        User fan = createUser("snapfan6");
        Project project = seedProject(owner, "Engaged");
        setVisibility(owner, project, "published");

        mockMvc.perform(post("/api/project/" + pid(project) + "/vote").header("Authorization", bearer(fan)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/project/" + pid(project) + "/comments")
                        .header("Authorization", bearer(fan))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Nice project"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/project/" + pid(project)).header("Authorization", bearer(fan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voteCount").value(1))
                .andExpect(jsonPath("$.commentCount").value(1));
    }

    @Test
    void publicCanReadOldVersionOfPublishedProject() throws Exception {
        User owner = createUser("snapowner7");
        Project project = seedProject(owner, "Linkable");

        setVisibility(owner, project, "published");
        String v1Json = mockMvc.perform(get("/api/project/" + pid(project) + "/versions")
                        .header("Authorization", bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        long v1Id = ((Number) com.jayway.jsonpath.JsonPath.read(v1Json, "$[0].id")).longValue();

        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/project/" + pid(project) + "/versions/" + v1Id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        setVisibility(owner, project, "private");
        mockMvc.perform(get("/api/public/project/" + pid(project) + "/versions/" + v1Id))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicOldVersionDeniedForNeverPublishedProject() throws Exception {
        User owner = createUser("snapowner8");
        Project project = seedProject(owner, "NeverPublished");

        mockMvc.perform(get("/api/public/project/" + pid(project) + "/versions/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void explorePublishedDateReflectsPublishHistory() throws Exception {
        User owner = createUser("snapowner11");
        Project project = seedProject(owner, "DatedProject");
        setVisibility(owner, project, "published");

        String feedJsonAfterFirstPublish = mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String feedIndexPath = "$.feed[?(@.title=='DatedProject')]";
        String publishedDate = com.jayway.jsonpath.JsonPath.<java.util.List<String>>read(
                feedJsonAfterFirstPublish, feedIndexPath + ".publishedDate").get(0);
        assertThat(com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(
                feedJsonAfterFirstPublish, feedIndexPath + ".lastPublishedDate").get(0)).isNull();

        setVisibility(owner, project, "private");
        setVisibility(owner, project, "published");

        String feedJsonAfterRepublish = mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(com.jayway.jsonpath.JsonPath.<java.util.List<String>>read(
                feedJsonAfterRepublish, feedIndexPath + ".publishedDate").get(0)).isEqualTo(publishedDate);
        assertThat(com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(
                feedJsonAfterRepublish, feedIndexPath + ".lastPublishedDate").get(0)).isNotNull();
    }
}
