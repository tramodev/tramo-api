package com.tramo.backend.trail;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EditorHotPathLoadCountTest extends AbstractIntegrationTest {

    private static final long AUTOSAVE_MAX_ENTITY_LOADS = 4;

    private long entityLoadCount(HttpCall call) throws Exception {
        org.hibernate.stat.Statistics statistics =
                entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.clear();
        call.run();
        return statistics.getEntityLoadCount();
    }


    private long createTrail(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"%s"}""".formatted(title));
    }

    private long createItem(User owner, long trailId, String title) throws Exception {
        return postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"%s"}""".formatted(title));
    }

    private void saveContent(User owner, long itemId, String text) throws Exception {
        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"%s"}""".formatted(text)))
                .andExpect(status().isNoContent());
    }

    @Test
    void contentSaveQueryCountDoesNotScaleWithTrailSize() throws Exception {
        User owner = createUser("ehpqcsize");
        Project project = createProject(owner, "Autosave", "private");
        long smallTrail = createTrail(owner, project, "Small");
        long smallItem = createItem(owner, smallTrail, "Only item");

        long largeTrail = createTrail(owner, project, "Large");
        long largeItem = createItem(owner, largeTrail, "First item");
        for (int i = 1; i < 8; i++) {
            createItem(owner, largeTrail, "Filler " + i);
        }

        saveContent(owner, smallItem, "warmup");
        saveContent(owner, largeItem, "warmup");

        long small = entityLoadCount(() -> saveContent(owner, smallItem, "small edit"));
        long large = entityLoadCount(() -> saveContent(owner, largeItem, "large edit"));

        assertThat(large).isEqualTo(small);
        assertThat(small).isLessThanOrEqualTo(AUTOSAVE_MAX_ENTITY_LOADS);
    }

    @Test
    void updateStepQueryCountDoesNotScaleWithTrailSize() throws Exception {
        User owner = createUser("ehpqcstep");
        Project project = createProject(owner, "Steps", "private");
        long smallTrail = createTrail(owner, project, "Small");
        long smallItem = createItem(owner, smallTrail, "Only item");

        long largeTrail = createTrail(owner, project, "Large");
        long largeItem = createItem(owner, largeTrail, "First item");
        for (int i = 1; i < 8; i++) {
            createItem(owner, largeTrail, "Filler " + i);
        }

        long small = entityLoadCount(() -> mockMvc.perform(put("/api/trail/" + smallTrail + "/item/" + smallItem)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"annotation":"note"}"""))
                .andExpect(status().isNoContent()));

        long large = entityLoadCount(() -> mockMvc.perform(put("/api/trail/" + largeTrail + "/item/" + largeItem)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"annotation":"note"}"""))
                .andExpect(status().isNoContent()));

        assertThat(large).isEqualTo(small);
    }
}
