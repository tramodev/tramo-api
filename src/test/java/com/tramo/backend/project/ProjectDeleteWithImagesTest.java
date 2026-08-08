package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.time.Duration;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectDeleteWithImagesTest extends AbstractIntegrationTest {

    private static final String IMAGE_URL = "https://test-bucket.example.com/editor-image/1/abcdef.jpg";

    @Autowired
    private ItemService itemService;

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

        jdbcTemplate.update("UPDATE pending_image_deletion SET requested_at = ? WHERE url = ?",
                new Date(System.currentTimeMillis() - Duration.ofDays(2).toMillis()), IMAGE_URL);
        itemService.purgePendingImageDeletions();

        ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(deleted.capture());
        assertThat(deleted.getValue().key()).isEqualTo("editor-image/1/abcdef.jpg");

        Long stillQueued = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pending_image_deletion WHERE url = ?", Long.class, IMAGE_URL);
        assertThat(stillQueued).isZero();
    }

    private String objectMapperWrite(String lexicalJson) {
        return "{\"content\":%s}".formatted(
                "\"" + lexicalJson.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    }
}
