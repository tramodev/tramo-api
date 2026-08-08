package com.tramo.backend.trail;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeleteReferencedTrailTest extends AbstractIntegrationTest {

    @Test
    void deletesTrailUsedAsProjectThumbnail() throws Exception {
        User owner = createUser("drtthumb");
        Project project = createProject(owner, "Thumb", "private", "A description", null);
        long trailId = postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"T"}""");
        postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"Item"}""");

        mockMvc.perform(put("/api/project/" + pid(project) + "/thumbnail")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GRAPH","trailId":"%d"}""".formatted(trailId)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/trail/" + trailId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletesTrailThatAnotherProjectForkedFrom() throws Exception {
        User owner = createUser("drtforkowner");
        User forker = createUser("drtforker");
        Project project = createProject(owner, "Forkable", "private", "A description", null);
        long trailId = postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"T"}""");
        postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"Item"}""");

        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"published"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/project/" + pid(project) + "/fork").header("Authorization", bearer(forker)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/trail/" + trailId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
    }
}
