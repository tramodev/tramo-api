package com.tramo.backend.trail;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.trail.entity.ItemImageReference;
import com.tramo.backend.trail.repository.ItemImageReferenceRepository;
import com.tramo.backend.trail.service.ItemImageReferenceBackfillRunner;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ItemImageReferenceBackfillTest extends AbstractIntegrationTest {

    @Autowired
    private ItemImageReferenceBackfillRunner runner;
    @Autowired
    private ItemImageReferenceRepository itemImageReferenceRepository;

    @Value("${app.r2.public-base-url}")
    private String r2PublicBaseUrl;

    private long seedItemWithContent(User owner, String content) throws Exception {
        Project project = createProject(owner, "Backfill", "private", "A description", null);
        long trailId = postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"T"}""");
        long itemId = postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"I"}""");
        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isNoContent());
        return itemId;
    }

    @Test
    void backfillInsertsReferencesForImagesAlreadyInContent() throws Exception {
        User owner = createUser("backfill1");
        String url = r2PublicBaseUrl + "/editor-image/999999/deadbeefcafefeed.jpg";
        seedItemWithContent(owner, "{\"content\":\"look at " + url + " here\"}");
        itemImageReferenceRepository.deleteAll();

        runner.run(null);

        List<ItemImageReference> refs = itemImageReferenceRepository.findAll();
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).getUrl()).isEqualTo(url);
    }

    @Test
    void backfillIsANoopWhenReferencesAlreadyExist() throws Exception {
        User owner = createUser("backfill2");
        String url = r2PublicBaseUrl + "/editor-image/999999/deadbeefcafefeed.jpg";
        seedItemWithContent(owner, "{\"content\":\"look at " + url + " here\"}");
        long before = itemImageReferenceRepository.count();
        assertThat(before).isEqualTo(1);

        runner.run(null);

        assertThat(itemImageReferenceRepository.count()).isEqualTo(before);
    }

    @Test
    void backfillIgnoresItemsWithoutImages() throws Exception {
        User owner = createUser("backfill3");
        seedItemWithContent(owner, "{\"content\":\"plain text only\"}");
        itemImageReferenceRepository.deleteAll();

        runner.run(null);

        assertThat(itemImageReferenceRepository.count()).isZero();
    }

    @Test
    void backfillIgnoresForeignImageUrls() throws Exception {
        User owner = createUser("backfill4");
        seedItemWithContent(owner, "{\"content\":\"see https://evil.example.com/a/b.png now\"}");
        itemImageReferenceRepository.deleteAll();

        runner.run(null);

        assertThat(itemImageReferenceRepository.count()).isZero();
    }
}
