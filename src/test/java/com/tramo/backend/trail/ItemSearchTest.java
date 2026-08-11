package com.tramo.backend.trail;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ItemSearchTest extends AbstractIntegrationTest {

    private static String lexical(String... texts) {
        String children = Arrays.stream(texts)
                .map(text -> "{\"text\":\"%s\",\"type\":\"text\",\"version\":1}".formatted(text))
                .collect(Collectors.joining(","));
        return "{\"root\":{\"children\":[{\"children\":[%s],\"type\":\"paragraph\",\"version\":1}],\"type\":\"root\",\"version\":1}}"
                .formatted(children);
    }

    private long createItem(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/item", """
                {"title":"%s"}""".formatted(title));
    }

    private void saveContent(User owner, long itemId, String lexicalJson) throws Exception {
        String escaped = lexicalJson.replace("\\", "\\\\").replace("\"", "\\\"");
        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"%s\"}".formatted(escaped)))
                .andExpect(status().isNoContent());
    }

    @Test
    void matchesWordSplitAcrossTextNodes() throws Exception {
        User owner = createUser("itemsearch1");
        Project project = createProject(owner, "Minerales", "private");
        long itemId = createItem(owner, project, "Piedra");
        saveContent(owner, itemId, lexical("el mineral de cuar", "zo"));
        createItem(owner, project, "Otra");

        mockMvc.perform(get("/api/project/" + pid(project) + "/item/search?q=cuarzo")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0]").value(itemId));
    }

    @Test
    void doesNotMatchStructuralJsonKeys() throws Exception {
        User owner = createUser("itemsearch2");
        Project project = createProject(owner, "Minerales", "private");
        long itemId = createItem(owner, project, "Piedra");
        saveContent(owner, itemId, lexical("nada relevante"));

        mockMvc.perform(get("/api/project/" + pid(project) + "/item/search?q=paragraph")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void ignoresQueriesShorterThanThreeChars() throws Exception {
        User owner = createUser("itemsearch3");
        Project project = createProject(owner, "Minerales", "private");
        long itemId = createItem(owner, project, "Piedra");
        saveContent(owner, itemId, lexical("vetas de cuarzo lechoso"));

        mockMvc.perform(get("/api/project/" + pid(project) + "/item/search?q=cu")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void deniesSearchOnSomeoneElseProject() throws Exception {
        User owner = createUser("itemsearch4");
        User stranger = createUser("itemsearch5");
        Project project = createProject(owner, "Minerales", "private");
        long itemId = createItem(owner, project, "Piedra");
        saveContent(owner, itemId, lexical("vetas de cuarzo lechoso"));

        mockMvc.perform(get("/api/project/" + pid(project) + "/item/search?q=cuarzo")
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
    }
}
