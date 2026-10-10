// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ForkContentTest extends AbstractIntegrationTest {

    @Autowired
    private TrailRepository trailRepository;
    @Autowired
    private TrailItemRepository trailItemRepository;
    @Autowired
    private ItemRepository itemRepository;

    private long createTrail(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"%s"}""".formatted(title));
    }

    private long createItem(User owner, long trailId, String title) throws Exception {
        return postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"%s"}""".formatted(title));
    }

    private void setContent(User owner, long itemId, String content) throws Exception {
        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isNoContent());
    }

    private void publish(User owner, Project project) throws Exception {
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
    }

    private Project forkOf(User forker, Project source) throws Exception {
        String forkId = postForProjectId(forker, "/api/project/" + pid(source) + "/fork", "");
        return projectRepository.findById(projectIdCodec.decode(forkId)).orElseThrow();
    }

    private List<Item> itemsOf(Project project) {
        return trailRepository.findByProjectId(project.getId()).stream()
                .flatMap(t -> trailItemRepository.findByTrailIdOrderByOrderIndexAsc(t.getId()).stream())
                .map(TrailItem::getItem)
                .toList();
    }

    @Test
    void forkFromLiveTablesCopiesItemContentAndTrailStructure() throws Exception {
        User owner = createUser("flowner3");
        User forker = createUser("flforker3");
        Project source = createProject(owner, "Structured", "unlisted", "A description", null);
        long trailId = createTrail(owner, source, "Chapter one");
        long itemId = createItem(owner, trailId, "Only item");
        setContent(owner, itemId, "live body");

        Project fork = forkOf(forker, source);

        List<Trail> forkTrails = trailRepository.findByProjectId(fork.getId());
        assertThat(forkTrails).hasSize(1);
        assertThat(forkTrails.get(0).getTitle()).isEqualTo("Chapter one");
        assertThat(forkTrails.get(0).getForkedFrom().getId()).isEqualTo(trailId);

        List<Item> copies = itemsOf(fork);
        assertThat(copies).hasSize(1);
        assertThat(copies.get(0).getTitle()).isEqualTo("Only item");
        assertThat(copies.get(0).getContent().getContent()).isEqualTo("live body");
    }

    @Test
    void forkFromSnapshotKeepsTrailOrder() throws Exception {
        User owner = createUser("fsowner2");
        User forker = createUser("fsforker2");
        Project source = createProject(owner, "Annotated", "private", "A description", null);
        long trailId = createTrail(owner, source, "T");
        long first = createItem(owner, trailId, "First");
        long second = createItem(owner, trailId, "Second");
        publish(owner, source);

        Project fork = forkOf(forker, source);

        long forkTrailId = trailRepository.findByProjectId(fork.getId()).get(0).getId();
        List<TrailItem> steps = trailItemRepository.findByTrailIdOrderByOrderIndexAsc(forkTrailId);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getItem().getTitle()).isEqualTo("First");
        assertThat(steps.get(1).getItem().getTitle()).isEqualTo("Second");
        assertThat(steps.get(0).getItem().getId()).isNotIn(first, second);
    }

    @Test
    void forkFromSnapshotCopiesPublishedContentAndTitleAlign() throws Exception {
        User owner = createUser("fsowner3");
        User forker = createUser("fsforker3");
        Project source = createProject(owner, "Content", "private", "A description", null);
        long trailId = createTrail(owner, source, "T");
        long itemId = createItem(owner, trailId, "Item");
        setContent(owner, itemId, "published body");
        publish(owner, source);
        setContent(owner, itemId, "draft body after publish");

        Project fork = forkOf(forker, source);

        List<Item> copies = itemsOf(fork);
        assertThat(copies).hasSize(1);
        assertThat(copies.get(0).getContent().getContent()).isEqualTo("published body");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void forkCopiesLooseNotes(boolean published) throws Exception {
        User owner = createUser("fsowner6");
        User forker = createUser("fsforker6");
        Project source = createProject(owner, "Loose", "unlisted", "A description", null);
        long trailId = createTrail(owner, source, "T");
        long filedId = createItem(owner, trailId, "Filed");
        long looseId = postForId(owner, "/api/project/" + pid(source) + "/item", """
                {"title":"Loose one"}""");
        setContent(owner, looseId, "loose body");
        if (published) publish(owner, source);

        Project fork = forkOf(forker, source);

        List<Item> all = itemRepository.findByProjectId(fork.getId());
        assertThat(all).extracting(Item::getTitle).containsExactlyInAnyOrder("Filed", "Loose one");

        Item looseCopy = all.stream().filter(i -> i.getTitle().equals("Loose one")).findFirst().orElseThrow();
        assertThat(looseCopy.getContent().getContent()).isEqualTo("loose body");
        assertThat(looseCopy.getProject().getId()).isEqualTo(fork.getId());
        assertThat(itemsOf(fork)).extracting(Item::getTitle).containsExactly("Filed");

    }

    @Test
    void forkedProjectIsPrivateAndOwnedByTheForker() throws Exception {
        User owner = createUser("fsowner4");
        User forker = createUser("fsforker4");
        Project source = createProject(owner, "Sourced", "published", "A description", "java");

        Project fork = forkOf(forker, source);

        assertThat(fork.getOwner().getId()).isEqualTo(forker.getId());
        assertThat(fork.getVisibility()).isEqualTo(com.tramo.backend.project.entity.ProjectVisibility.PRIVATE);
        assertThat(fork.getForkedFrom().getId()).isEqualTo(source.getId());
        assertThat(fork.getTitle()).isEqualTo("Sourced");
    }

    @Test
    void forkOfEmptyProjectProducesEmptyFork() throws Exception {
        User owner = createUser("fsowner5");
        User forker = createUser("fsforker5");
        Project source = createProject(owner, "Empty", "published", "A description", null);

        Project fork = forkOf(forker, source);

        assertThat(trailRepository.findByProjectId(fork.getId())).isEmpty();
        assertThat(itemsOf(fork)).isEmpty();
    }

    @Test
    void cannotForkYourOwnProject() throws Exception {
        User owner = createUser("fsowner6");
        Project source = createProject(owner, "Mine", "published", "A description", null);

        mockMvc.perform(post("/api/project/" + pid(source) + "/fork").header("Authorization", bearer(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotForkAPrivateProject() throws Exception {
        User owner = createUser("fsowner7");
        User forker = createUser("fsforker7");
        Project source = createProject(owner, "Secret", "private", "A description", null);

        mockMvc.perform(post("/api/project/" + pid(source) + "/fork").header("Authorization", bearer(forker)))
                .andExpect(status().isNotFound());
    }

    @Test
    void itemSharedByTwoTrailsIsCopiedOnce() throws Exception {
        User owner = createUser("fsowner8");
        User forker = createUser("fsforker8");
        Project source = createProject(owner, "Transcluded", "unlisted", "A description", null);
        long trailA = createTrail(owner, source, "A");
        long trailB = createTrail(owner, source, "B");
        long itemId = createItem(owner, trailA, "Shared");
        mockMvc.perform(post("/api/trail/" + trailB + "/item/" + itemId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        Project fork = forkOf(forker, source);

        List<Item> copies = itemsOf(fork);
        assertThat(copies).hasSize(2);
        assertThat(copies.stream().map(Item::getId).distinct()).hasSize(1);
    }
}
