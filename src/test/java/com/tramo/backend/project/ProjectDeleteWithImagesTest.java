package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectDeleteWithImagesTest extends AbstractIntegrationTest {

    private static final String IMAGE_URL = "https://test-bucket.example.com/editor-image/1/abcdef.jpg";

    @Test
    void deletesProjectWhoseItemsReferenceEditorImages() throws Exception {
        User owner = createUser("pdwiowner");
        Project project = createProject(owner, "With images", "private");
        long trailId = postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"T"}""");
        long itemId = postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"Has an image"}""");

        String content = """
                {"root":{"children":[{"type":"image","src":"%s"}]}}""".formatted(IMAGE_URL);
        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapperWrite(content)))
                .andExpect(status().isNoContent());

        Long refCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM item_image_reference WHERE item_id = ?", Long.class, itemId);
        assertThat(refCount).isEqualTo(1L);

        mockMvc.perform(delete("/api/project/" + pid(project)).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        Long remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM item_image_reference WHERE item_id = ?", Long.class, itemId);
        assertThat(remaining).isZero();

        Long queued = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pending_image_deletion WHERE url = ?", Long.class, IMAGE_URL);
        assertThat(queued).isEqualTo(1L);
    }

    private String objectMapperWrite(String lexicalJson) {
        return "{\"content\":%s}".formatted(
                "\"" + lexicalJson.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    }
}
