// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.dto.StartProjectRequest;
import com.tramo.backend.project.service.ProjectStartService;
import com.tramo.backend.trail.dto.ItemRequestDTO;
import com.tramo.backend.trail.dto.TrailRequestDTO;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.trail.service.TrailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;

class ProjectStartTest extends AbstractIntegrationTest {
    @Autowired private ProjectStartService starter;
    @Autowired private com.tramo.backend.project.service.ProjectService projectService;
    @Autowired private TrailService trailService;
    @Autowired private TrailRepository trails;
    @MockitoSpyBean private ItemService itemService;

    @Test
    void startEndpointValidatesRequestAndPreservesEmptyProjectCreation() throws Exception {
        var owner = createUser("endpointstarter");
        mockMvc.perform(post("/api/project/start").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        for (String invalid : new String[]{"{\"example\":false}", "{\"requestId\":\"invalid\",\"example\":false}"}) {
            mockMvc.perform(post("/api/project/start").header("Authorization", bearer(owner))
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        assertThat(projectRepository.count()).isZero();
        mockMvc.perform(post("/api/project").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Still empty\"}"))
                .andExpect(status().isOk());
        assertThat(trails.count()).isZero();
        mockMvc.perform(post("/api/project/start").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"example\":false}"))
                .andExpect(status().isOk());
        assertThat(projectRepository.count()).isEqualTo(2);
        assertThat(trails.count()).isEqualTo(1);
    }

    @Test
    void concurrentRetriesCreateOnePrivateProjectAndOpenTheSameNote() throws Exception {
        var owner = createUser("starter");
        var request = new StartProjectRequest(UUID.randomUUID());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> starter.create(request, owner));
            var second = executor.submit(() -> starter.create(request, owner));
            var result = first.get();
            assertThat(second.get()).isEqualTo(result);
            var project = projectRepository.findById(projectIdCodec.decode(result.projectId())).orElseThrow();
            assertThat(project.getVisibility().name()).isEqualTo("PRIVATE");
            assertThat(projectRepository.count()).isEqualTo(1);
            assertThat(itemService.getAllForTrail(result.trailId(), owner)).hasSize(1);
            assertThat(itemService.getContent(result.itemId(), owner).getContent()).isEmpty();
            var other = createUser("otherstarter");
            assertThat(starter.create(request, other).projectId()).isNotEqualTo(result.projectId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedInitializationRollsBackAndCanBeRetried() {
        var owner = createUser("rollbackstarter");
        var request = new StartProjectRequest(UUID.randomUUID());
        doThrow(new IllegalStateException("injected failure")).when(itemService).create(anyLong(), any(ItemRequestDTO.class), any());
        assertThatThrownBy(() -> starter.create(request, owner)).isInstanceOf(IllegalStateException.class);
        assertThat(projectRepository.count()).isZero();
        assertThat(trails.count()).isZero();
        doCallRealMethod().when(itemService).create(anyLong(), any(ItemRequestDTO.class), any());
        starter.create(request, owner);
        assertThat(projectRepository.count()).isEqualTo(1);
    }

    @Test
    void existingEmptyProjectReusesItsTrailAndRequiresOwnership() {
        var owner = createUser("emptystarter");
        var project = createProject(owner, "Existing", "private");
        var request = new TrailRequestDTO();
        request.setTitle("Existing trail");
        var trail = trailService.create(project.getId(), request, owner);
        var result = starter.start(project.getId(), trail.getId(), owner);
        assertThat(result.trailId()).isEqualTo(trail.getId());
        assertThat(starter.start(project.getId(), trail.getId(), owner)).isEqualTo(result);
        assertThat(trails.count()).isEqualTo(1);
        var stranger = createUser("strangerstarter");
        assertThatThrownBy(() -> starter.start(project.getId(), null, stranger)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void exampleSurvivesRenameAndCanBeRecreatedAfterDeletion() {
        var owner = createUser("examplelifecycle");
        var result = starter.createExample(owner);
        Long id = projectIdCodec.decode(result.projectId());
        var project = projectRepository.findById(id).orElseThrow();
        project.setTitle("My edited example");
        projectRepository.save(project);
        assertThat(starter.createExample(owner)).isEqualTo(result);
        projectService.delete(id, owner);
        assertThat(starter.createExample(owner).projectId()).isNotEqualTo(result.projectId());
        assertThat(projectRepository.count()).isEqualTo(1);
    }

    @Test
    void reopeningExampleUsesConstantQueriesAsContentGrows() throws Exception {
        var owner = createUser("examplequeries");
        var result = starter.createExample(owner);
        long before = queryCount(() -> starter.createExample(owner));
        for (int i = 0; i < 20; i++) {
            var request = new ItemRequestDTO();
            request.setTitle("Extra note " + i);
            itemService.create(result.trailId(), request, owner);
        }
        long after = queryCount(() -> starter.createExample(owner));
        assertThat(after).isEqualTo(before);
        assertThat(after).isLessThanOrEqualTo(8);
    }

    @Test
    void concurrentExampleRequestsReuseOneProjectPerOwner() throws Exception {
        var owner = createUser("concurrentexample");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> starter.createExample(owner));
            var second = executor.submit(() -> starter.createExample(owner));
            var result = first.get();
            assertThat(second.get()).isEqualTo(result);
            assertThat(projectRepository.count()).isEqualTo(1);
            var other = createUser("otherexample");
            assertThat(starter.createExample(other).projectId()).isNotEqualTo(result.projectId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void exampleEndpointRequiresAuthenticationAndReusesProject() throws Exception {
        mockMvc.perform(post("/api/project/example")).andExpect(status().isUnauthorized());
        var owner = createUser("exampleendpoint");
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/project/example").header("Authorization", bearer(owner)))
                    .andExpect(status().isOk());
        }
        assertThat(projectRepository.count()).isEqualTo(1);
    }

    @Test
    void failedExampleInitializationRollsBackAndCanBeRetried() {
        var owner = createUser("rollbackexample");
        doThrow(new IllegalStateException("injected failure")).when(itemService).create(anyLong(), any(ItemRequestDTO.class), any());
        assertThatThrownBy(() -> starter.createExample(owner)).isInstanceOf(IllegalStateException.class);
        assertThat(projectRepository.count()).isZero();
        doCallRealMethod().when(itemService).create(anyLong(), any(ItemRequestDTO.class), any());
        starter.createExample(owner);
        assertThat(projectRepository.count()).isEqualTo(1);
    }

    @Test
    void exampleSharesOneNoteAndEditsAppearInBothTrails() {
        var owner = createUser("examplestarter");
        var result = starter.createExample(owner);
        Long projectId = projectIdCodec.decode(result.projectId());
        var projectTrails = trailService.getAllForProject(projectId, owner);
        assertThat(projectTrails).hasSize(2);
        Long basics = projectTrails.stream().filter(t -> t.getTitle().equals("Bush’s vision")).findFirst().orElseThrow().getId();
        Long tokens = projectTrails.stream().filter(t -> t.getTitle().equals("Thinking in trails")).findFirst().orElseThrow().getId();
        var basicNotes = itemService.getAllForTrail(basics, owner);
        var tokenNotes = itemService.getAllForTrail(tokens, owner);
        assertThat(itemService.getItemsForProject(projectId, owner)).hasSize(5);
        Long shared = basicNotes.get(2).id();
        assertThat(tokenNotes.get(0).id()).isEqualTo(shared);
        assertThat(itemService.getContent(basicNotes.get(1).id(), owner).getContent())
                .contains("\"rel\":\"tramo-idea:" + basicNotes.get(0).id() + "\"");
        assertThat(itemService.getContent(tokenNotes.get(1).id(), owner).getContent())
                .contains("\"rel\":\"tramo-idea:" + shared + "\"");
        assertThat(itemService.getAssociations(basicNotes.get(1).id(), owner)).isEmpty();
        assertThat(itemService.getAssociations(tokenNotes.get(1).id(), owner)).isEmpty();
        String edited = "{\"root\":{\"children\":[{\"text\":\"Edited Memex\"}]}}";
        itemService.updateContent(shared, edited, owner);
        for (Long trailId : new Long[]{basics, tokens}) {
            assertThat(itemService.getContentsForTrail(trailId, owner).stream().filter(note -> note.id().equals(shared)).findFirst().orElseThrow().content()).isEqualTo(edited);
        }
        itemService.detachFromTrail(tokens, shared, owner);
        assertThat(itemService.getContent(shared, owner).getContent()).isEqualTo(edited);
        assertThat(itemService.getAllForTrail(basics, owner)).hasSize(3);
        var independent = starter.createExample(owner);
        assertThat(independent).isEqualTo(result);
        assertThat(projectRepository.count()).isEqualTo(1);
    }
}
