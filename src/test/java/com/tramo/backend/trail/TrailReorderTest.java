package com.tramo.backend.trail;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TrailReorderTest extends AbstractIntegrationTest {

    private long createTrail(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"%s"}""".formatted(title));
    }

    private long createItem(User owner, long trailId, String title) throws Exception {
        return postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"%s"}""".formatted(title));
    }

    private void reorder(User asUser, long trailId, String body, int expectedStatus) throws Exception {
        mockMvc.perform(put("/api/trail/" + trailId + "/item/order")
                        .header("Authorization", bearer(asUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void reordersTrailItems() throws Exception {
        User owner = createUser("reorder1");
        Project project = createProject(owner, "Reorder", "private");
        long trailId = createTrail(owner, project, "T");
        long first = createItem(owner, trailId, "First");
        long second = createItem(owner, trailId, "Second");
        long third = createItem(owner, trailId, "Third");

        reorder(owner, trailId, """
                {"itemIds":[%d,%d,%d]}""".formatted(third, first, second), 204);

        mockMvc.perform(get("/api/trail/" + trailId + "/item")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains((int) third, (int) first, (int) second)));
    }

    @Test
    void rejectsAnOrderThatIsNotAPermutation() throws Exception {
        User owner = createUser("reorder2");
        Project project = createProject(owner, "Reorder", "private");
        long trailId = createTrail(owner, project, "T");
        long first = createItem(owner, trailId, "First");
        createItem(owner, trailId, "Second");

        reorder(owner, trailId, """
                {"itemIds":[%d]}""".formatted(first), 400);
    }

    @Test
    void deniesReorderOnSomeoneElseTrail() throws Exception {
        User owner = createUser("reorder3");
        User stranger = createUser("reorder4");
        Project project = createProject(owner, "Reorder", "private");
        long trailId = createTrail(owner, project, "T");
        long first = createItem(owner, trailId, "First");

        reorder(stranger, trailId, """
                {"itemIds":[%d]}""".formatted(first), 403);
    }
}
