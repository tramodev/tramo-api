package com.tramo.backend.trail;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeleteItemWithReferencedAssociationTest extends AbstractIntegrationTest {

    @Test
    void deletesItemWhoseAssociationIsUsedAsAnotherStepArrival() throws Exception {
        User owner = createUser("dirassoc");
        Project project = createProject(owner, "Assoc", "private");
        long trailId = postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"T"}""");
        long source = postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"Source"}""");
        long target = postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"Target"}""");

        mockMvc.perform(post("/api/item/" + source + "/tie")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"ITEM","targetId":%d,"type":"RELATED"}""".formatted(target)))
                .andExpect(status().isNoContent());

        String associations = mockMvc.perform(get("/api/item/" + source + "/association")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String associationId = JsonPath.read(associations, "$[0].id");

        mockMvc.perform(put("/api/trail/" + trailId + "/item/" + target)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"annotation":"arrived here","associationId":%s}""".formatted(associationId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/item/" + source).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
    }
}
