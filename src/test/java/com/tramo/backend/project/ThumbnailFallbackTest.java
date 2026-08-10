package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ThumbnailFallbackTest extends AbstractIntegrationTest {

    @Value("${app.r2.public-base-url}")
    private String r2PublicBaseUrl;

    private long createTrail(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"%s"}""".formatted(title));
    }

    private long createItem(User owner, long trailId, String title) throws Exception {
        return postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"%s"}""".formatted(title));
    }

    private void setContent(User owner, long itemId, String content) throws Exception {
        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void projectWithoutAGraphFallsBackToItsFirstItemImage() throws Exception {
        User owner = createUser("thumbfb1");
        Project project = createProject(owner, "Illustrated", "private", "A description", null);
        long trailId = createTrail(owner, project, "T");
        long itemId = createItem(owner, trailId, "Only item");
        String url = r2PublicBaseUrl + "/editor-image/999999/deadbeefcafefeed.jpg";
        setContent(owner, itemId, "look at " + url + " here");

        mockMvc.perform(get("/api/project").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].thumbnailImageUrl").value(url))
                .andExpect(jsonPath("$[0].thumbnailGraph").value(nullValue()));
    }

    @Test
    void projectWithConnectedItemsPrefersTheGraphOverAnImage() throws Exception {
        User owner = createUser("thumbfb2");
        Project project = createProject(owner, "Connected", "private", "A description", null);
        long trailId = createTrail(owner, project, "T");
        long itemA = createItem(owner, trailId, "A");
        long itemB = createItem(owner, trailId, "B");
        String url = r2PublicBaseUrl + "/editor-image/999999/deadbeefcafefeed.jpg";
        setContent(owner, itemA, "look at " + url + " here");
        mockMvc.perform(post("/api/item/" + itemA + "/tie")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REQUIRES\",\"targetType\":\"ITEM\",\"targetId\":" + itemB + "}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/project").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].thumbnailImageUrl").value(nullValue()))
                .andExpect(jsonPath("$[0].thumbnailGraph.trailId").value(String.valueOf(trailId)));
    }

    @Test
    void emptyProjectHasNoThumbnailAtAll() throws Exception {
        User owner = createUser("thumbfb3");
        createProject(owner, "Bare", "private", "A description", null);

        mockMvc.perform(get("/api/project").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].thumbnailImageUrl").value(nullValue()))
                .andExpect(jsonPath("$[0].thumbnailGraph").value(nullValue()));
    }

    @Test
    void graphThumbnailPointingAtADeletedTrailFallsBackToEmpty() throws Exception {
        User owner = createUser("thumbfb4");
        Project project = createProject(owner, "Dangling", "private", "A description", null);
        long trailId = createTrail(owner, project, "T");
        createItem(owner, trailId, "I");
        mockMvc.perform(put("/api/project/" + pid(project) + "/thumbnail")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"GRAPH\",\"trailId\":\"" + trailId + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/trail/" + trailId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/project").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].thumbnailGraph").value(nullValue()));
    }
}
