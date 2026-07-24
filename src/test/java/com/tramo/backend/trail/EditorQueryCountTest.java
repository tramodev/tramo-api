package com.tramo.backend.trail;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Guards the editor read endpoints (trails/items/associations of a project the
// requester owns) against N+1: the query count must not grow with the number of
// rows returned. Same statistics-based approach as project/QueryCountTest.
class EditorQueryCountTest extends AbstractIntegrationTest {

    private long createTrail(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"%s"}""".formatted(title));
    }

    private long createItem(User owner, long trailId, String title) throws Exception {
        return postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"%s"}""".formatted(title));
    }

    private void tie(User owner, long sourceItem, long targetItem) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/item/" + sourceItem + "/tie")
                        .header("Authorization", bearer(owner))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"RELATED\",\"targetType\":\"ITEM\",\"targetId\":" + targetItem + "}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void getAllForTrailQueryCountDoesNotScaleWithItemCount() throws Exception {
        User owner = createUser("eqcowner1");
        Project project = createProject(owner, "TrailItems", "private");
        long trailId = createTrail(owner, project, "T");
        createItem(owner, trailId, "Item 0");

        long small = queryCount(() -> mockMvc.perform(get("/api/trail/" + trailId + "/item")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            createItem(owner, trailId, "Item " + i);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/trail/" + trailId + "/item")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void getItemsForProjectQueryCountDoesNotScaleWithItemCount() throws Exception {
        User owner = createUser("eqcowner2");
        Project project = createProject(owner, "ProjectItems", "private");
        long trailId = createTrail(owner, project, "T");
        createItem(owner, trailId, "Item 0");

        long small = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/item")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            createItem(owner, trailId, "Item " + i);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/item")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void getAllTrailsForProjectQueryCountDoesNotScaleWithTrailCount() throws Exception {
        User owner = createUser("eqcowner3");
        Project project = createProject(owner, "ProjectTrails", "private");
        createTrail(owner, project, "Trail 0");

        long small = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/trail")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            createTrail(owner, project, "Trail " + i);
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/project/" + pid(project) + "/trail")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }

    @Test
    void getAssociationsQueryCountDoesNotScaleWithAssociationCount() throws Exception {
        User owner = createUser("eqcowner4");
        Project project = createProject(owner, "Associations", "private");
        long trailId = createTrail(owner, project, "T");
        long source = createItem(owner, trailId, "Source");
        tie(owner, source, createItem(owner, trailId, "Target 0"));

        long small = queryCount(() -> mockMvc.perform(get("/api/item/" + source + "/association")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        for (int i = 1; i < 6; i++) {
            tie(owner, source, createItem(owner, trailId, "Target " + i));
        }

        long large = queryCount(() -> mockMvc.perform(get("/api/item/" + source + "/association")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()));

        assertThat(large).isEqualTo(small);
    }
}
