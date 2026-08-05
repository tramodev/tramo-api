package com.tramo.backend.project;

import com.jayway.jsonpath.JsonPath;
import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;






class PublicProjectContentTest extends AbstractIntegrationTest {

    private long createTrail(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"%s"}""".formatted(title));
    }

    private long createItem(User owner, long trailId, String title) throws Exception {
        return postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"%s"}""".formatted(title));
    }

    private ResultActions tie(User owner, long sourceItem, long targetId) throws Exception {
        return mockMvc.perform(post("/api/item/" + sourceItem + "/tie")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"RELATED\",\"targetType\":\"ITEM\",\"targetId\":" + targetId + "}"));
    }

    @Test
    void publicProjectExposesThumbnailImageUrlWhenDedicatedButNotForGraphOrNone() throws Exception {
        User owner = createUser("publicthumbowner");
        Project project = createProject(owner, "Thumbed", "published");

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thumbnailImageUrl").value(nullValue()));

        mockMvc.perform(put("/api/project/" + pid(project) + "/thumbnail")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEDICATED","imageUrl":"https://example.com/thumb.png"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thumbnailImageUrl").value("https://example.com/thumb.png"));
    }

    @Test
    void publicProjectExposesTrailAndStepMetadataButFiltersCrossProjectAssociations() throws Exception {
        User owner = createUser("publicdetailowner");
        Project project = createProject(owner, "Detailed", "published", "desc", "tag");
        long trailId = createTrail(owner, project, "Main trail");
        long itemA = createItem(owner, trailId, "A");
        long itemB = createItem(owner, trailId, "B");

        mockMvc.perform(put("/api/trail/" + trailId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"A trail about A and B"}"""))
                .andExpect(status().isOk());

        tie(owner, itemA, itemB).andExpect(status().isNoContent());

        String assocResponse = mockMvc.perform(get("/api/item/" + itemA + "/association")
                        .header("Authorization", bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        String assocId = JsonPath.read(assocResponse, "$[0].id");

        mockMvc.perform(put("/api/trail/" + trailId + "/item/" + itemB)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"annotation\":\"because A leads here\",\"associationId\":" + assocId + "}"))
                .andExpect(status().isNoContent());

        
        Project otherProject = createProject(owner, "Unrelated", "private");
        long otherTrailId = createTrail(owner, otherProject, "Other trail");
        long itemC = createItem(owner, otherTrailId, "Secret item C");
        tie(owner, itemA, itemC).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/public/project/" + pid(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trails[0].description").value("A trail about A and B"))
                .andExpect(jsonPath("$.trails[0].version").value(1))
                .andExpect(jsonPath("$.trails[0].forkedFromId").value(nullValue()))
                .andExpect(jsonPath("$.trails[0].items[1].annotation").value("because A leads here"))
                .andExpect(jsonPath("$.trails[0].items[1].associationId").value(assocId))
                .andExpect(jsonPath("$.trails[0].items[0].associations.length()").value(1))
                .andExpect(jsonPath("$.trails[0].items[0].associations[0].targetTitle").value("B"))
                .andExpect(jsonPath("$.trails[0].items[0].associations[0].targetId").value(String.valueOf(itemB)));
    }
}
