package com.tramo.backend.trail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MapPreviewTest extends AbstractIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsCrossTrailAndLoosePreviewsOnlyToOwner() throws Exception {
        User owner = createUser("mapowner");
        User stranger = createUser("mapstranger");
        Project project = createProject(owner, "Map", "private");
        long firstTrail = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"First\"}");
        long secondTrail = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Second\"}");
        long source = postForId(owner, "/api/trail/" + firstTrail + "/item", "{\"title\":\"Source\"}");
        long target = postForId(owner, "/api/trail/" + secondTrail + "/item", "{\"title\":\"Target\"}");
        long loose = postForId(owner, "/api/project/" + pid(project) + "/item", "{\"title\":\"Loose\"}");
        mockMvc.perform(post("/api/trail/" + secondTrail + "/item/" + source)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        String content = "{\"root\":{\"children\":[{\"type\":\"paragraph\",\"children\":[{\"type\":\"text\",\"text\":\"From first\"}]},{\"type\":\"paragraph\",\"children\":[{\"type\":\"link\",\"rel\":\"tramo-idea:" + target + "\",\"children\":[{\"type\":\"text\",\"text\":\"Second link\"}]}]}]}}";
        mockMvc.perform(put("/api/item/" + source + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/project/" + pid(project) + "/map-preview")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['" + source + "'].text").value("From first\n\nSecond link"))
                .andExpect(jsonPath("$['" + source + "'].linkedItemIds[0]").value(String.valueOf(target)))
                .andExpect(jsonPath("$['" + target + "'].text").value(""))
                .andExpect(jsonPath("$['" + loose + "'].linkedItemIds").isEmpty());

        mockMvc.perform(get("/api/project/" + pid(project) + "/map-preview")
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
    }
}
