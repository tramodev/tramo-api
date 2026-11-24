package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.project.dto.ProjectImportRequest;
import com.tramo.backend.project.dto.ProjectImportRequest.ImportItem;
import com.tramo.backend.project.dto.ProjectImportRequest.ImportTrail;
import com.tramo.backend.project.service.ProjectStartService;
import com.tramo.backend.trail.dto.TrailItemDTO;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.trail.service.ItemService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectImportTest extends AbstractIntegrationTest {
    @Autowired private ProjectStartService starter;
    @Autowired private ItemService itemService;
    @Autowired private TrailRepository trails;
    @Autowired private ItemRepository items;
    @Autowired private ProjectIdCodec codec;

    private static String mention(String before, String targetId, String title) {
        return "{\"root\":{\"type\":\"root\",\"version\":1,\"children\":[{\"type\":\"paragraph\",\"version\":1,\"children\":["
                + "{\"type\":\"text\",\"version\":1,\"text\":\"" + before + "\",\"format\":0,\"mode\":\"normal\",\"style\":\"\",\"detail\":0},"
                + "{\"type\":\"link\",\"version\":1,\"url\":\"#\",\"rel\":\"tramo-idea:" + targetId + "\",\"children\":["
                + "{\"type\":\"text\",\"version\":1,\"text\":\"" + title + "\",\"format\":0,\"mode\":\"normal\",\"style\":\"\",\"detail\":0}]}]}]}}";
    }

    private ProjectImportRequest playground(UUID id) {
        return new ProjectImportRequest(id, "Playground",
                List.of(new ImportTrail("First", List.of("1", "2")), new ImportTrail("Second", List.of("3", "2"))),
                List.of(new ImportItem("1", "Intro", "left", mention("See ", "3", "Details")),
                        new ImportItem("2", "Shared", "center", lexicalText("shared")),
                        new ImportItem("3", "Details", "right", mention("Missing ", "99", "Gone")),
                        new ImportItem("4", "", "center", "")));
    }

    @Test
    void importsTrailsSharedAndLooseNotesAndRemapsMentions() {
        var owner = createUser("importowner");
        var result = starter.importProject(playground(UUID.randomUUID()), owner);
        Long projectId = codec.decode(result.projectId());

        var projectTrails = trails.findByProjectId(projectId);
        assertThat(projectTrails).extracting(trail -> trail.getTitle()).containsExactly("First", "Second");
        List<TrailItemDTO> first = itemService.getAllForTrail(projectTrails.get(0).getId(), owner);
        List<TrailItemDTO> second = itemService.getAllForTrail(projectTrails.get(1).getId(), owner);
        assertThat(first).extracting(TrailItemDTO::title).containsExactly("Intro", "Shared");
        assertThat(second).extracting(TrailItemDTO::title).containsExactly("Details", "Shared");
        assertThat(second.get(1).id()).isEqualTo(first.get(1).id());
        assertThat(first.get(0).titleAlign()).isEqualTo("left");
        assertThat(second.get(0).titleAlign()).isEqualTo("right");
        assertThat(items.findByProjectId(projectId)).hasSize(4).anyMatch(item -> item.getTitle().equals("Untitled note"));

        assertThat(result.trailId()).isEqualTo(projectTrails.get(0).getId());
        assertThat(result.itemId()).isEqualTo(first.get(0).id());
        assertThat(itemService.getContent(first.get(0).id(), owner).getContent()).contains("\"rel\":\"tramo-idea:" + second.get(0).id() + "\"");
        assertThat(itemService.getContent(second.get(0).id(), owner).getContent()).doesNotContain("tramo-idea:").contains("Gone");
    }

    @Test
    void retryReturnsTheSameProject() {
        var owner = createUser("importretry");
        var request = playground(UUID.randomUUID());
        var first = starter.importProject(request, owner);
        assertThat(starter.importProject(request, owner)).isEqualTo(first);
        assertThat(projectRepository.count()).isEqualTo(1);
    }

    @Test
    void endpointRejectsUnknownOrDuplicateNotesWithoutCreating() throws Exception {
        var owner = createUser("importinvalid");
        var mapper = new tools.jackson.databind.ObjectMapper();
        var note = new ImportItem("1", "Note", "center", lexicalText("text"));
        for (var request : List.of(
                new ProjectImportRequest(UUID.randomUUID(), "", List.of(new ImportTrail("T", List.of("2"))), List.of(note)),
                new ProjectImportRequest(UUID.randomUUID(), "", List.of(new ImportTrail("T", List.of("1", "1"))), List.of(note)),
                new ProjectImportRequest(UUID.randomUUID(), "", List.of(), List.of(note, note)),
                new ProjectImportRequest(UUID.randomUUID(), "", List.of(), List.of()))) {
            mockMvc.perform(post("/api/project/import").header("Authorization", bearer(owner))
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(projectRepository.count()).isZero();
    }

    @Test
    void endpointRollsBackWhenANoteIsInvalid() throws Exception {
        var owner = createUser("importrollback");
        var mapper = new tools.jackson.databind.ObjectMapper();
        var request = new ProjectImportRequest(UUID.randomUUID(), "P", List.of(new ImportTrail("T", List.of("1", "2"))),
                List.of(new ImportItem("1", "Ok", "center", lexicalText("fine")), new ImportItem("2", "Bad", "center", "{\"root\":42}")));
        mockMvc.perform(post("/api/project/import").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        assertThat(projectRepository.count()).isZero();
    }
}
