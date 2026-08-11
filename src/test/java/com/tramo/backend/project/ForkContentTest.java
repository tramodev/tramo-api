package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.trail.entity.Association;
import com.tramo.backend.trail.entity.AssociationTargetType;
import com.tramo.backend.trail.entity.AssociationType;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.repository.AssociationRepository;
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
    @Autowired
    private AssociationRepository associationRepository;

    private long createTrail(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"%s"}""".formatted(title));
    }

    private long createItem(User owner, long trailId, String title) throws Exception {
        return postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"%s"}""".formatted(title));
    }

    private ResultActions tie(User owner, long sourceItem, String type, String targetType, long targetId) throws Exception {
        return mockMvc.perform(post("/api/item/" + sourceItem + "/tie")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"" + type + "\",\"targetType\":\"" + targetType + "\",\"targetId\":" + targetId + "}"));
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
    void forkFromLiveTablesCopiesItemAssociationsRetargetedToTheCopies() throws Exception {
        User owner = createUser("flowner1");
        User forker = createUser("flforker1");
        Project source = createProject(owner, "Unpublished", "unlisted", "A description", null);
        long trailId = createTrail(owner, source, "T");
        long itemA = createItem(owner, trailId, "A");
        long itemB = createItem(owner, trailId, "B");
        tie(owner, itemA, "REQUIRES", "ITEM", itemB).andExpect(status().isNoContent());

        Project fork = forkOf(forker, source);

        List<Item> copies = itemsOf(fork);
        assertThat(copies).hasSize(2);
        List<Long> copyIds = copies.stream().map(Item::getId).toList();
        assertThat(copyIds).doesNotContain(itemA, itemB);

        Item copyA = copies.stream().filter(i -> i.getTitle().equals("A")).findFirst().orElseThrow();
        Item copyB = copies.stream().filter(i -> i.getTitle().equals("B")).findFirst().orElseThrow();
        List<Association> copied = associationRepository.findBySourceItemId(copyA.getId());
        assertThat(copied).hasSize(1);
        assertThat(copied.get(0).getTargetId()).isEqualTo(copyB.getId());
        assertThat(copied.get(0).getTargetType()).isEqualTo(AssociationTargetType.ITEM);
        assertThat(copied.get(0).getType()).isEqualTo(AssociationType.REQUIRES);
    }

    @Test
    void forkFromLiveTablesRetargetsTrailAssociationsToTheCopiedTrail() throws Exception {
        User owner = createUser("flowner2");
        User forker = createUser("flforker2");
        Project source = createProject(owner, "Trail linked", "unlisted", "A description", null);
        long trailId = createTrail(owner, source, "T");
        long otherTrailId = createTrail(owner, source, "Other");
        long itemA = createItem(owner, trailId, "A");
        tie(owner, itemA, "RELATED", "TRAIL", otherTrailId).andExpect(status().isNoContent());

        Project fork = forkOf(forker, source);

        Item copyA = itemsOf(fork).stream().filter(i -> i.getTitle().equals("A")).findFirst().orElseThrow();
        List<Association> copied = associationRepository.findBySourceItemId(copyA.getId());
        assertThat(copied).hasSize(1);
        assertThat(copied.get(0).getTargetType()).isEqualTo(AssociationTargetType.TRAIL);
        assertThat(copied.get(0).getTargetId()).isNotEqualTo(otherTrailId);

        List<Long> forkTrailIds = trailRepository.findByProjectId(fork.getId()).stream().map(Trail::getId).toList();
        assertThat(forkTrailIds).contains(copied.get(0).getTargetId());
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
    void forkFromSnapshotCopiesAssociationsRetargetedToTheCopies() throws Exception {
        User owner = createUser("fsowner1");
        User forker = createUser("fsforker1");
        Project source = createProject(owner, "Published", "private", "A description", null);
        long trailId = createTrail(owner, source, "T");
        long itemA = createItem(owner, trailId, "A");
        long itemB = createItem(owner, trailId, "B");
        tie(owner, itemA, "REQUIRES", "ITEM", itemB).andExpect(status().isNoContent());
        publish(owner, source);

        Project fork = forkOf(forker, source);

        List<Item> copies = itemsOf(fork);
        assertThat(copies).hasSize(2);
        Item copyA = copies.stream().filter(i -> i.getTitle().equals("A")).findFirst().orElseThrow();
        Item copyB = copies.stream().filter(i -> i.getTitle().equals("B")).findFirst().orElseThrow();

        List<Association> copied = associationRepository.findBySourceItemId(copyA.getId());
        assertThat(copied).hasSize(1);
        assertThat(copied.get(0).getTargetId()).isEqualTo(copyB.getId());
        assertThat(copied.get(0).getType()).isEqualTo(AssociationType.REQUIRES);
    }

    @Test
    void forkFromSnapshotKeepsTrailOrderAndAnnotations() throws Exception {
        User owner = createUser("fsowner2");
        User forker = createUser("fsforker2");
        Project source = createProject(owner, "Annotated", "private", "A description", null);
        long trailId = createTrail(owner, source, "T");
        long first = createItem(owner, trailId, "First");
        long second = createItem(owner, trailId, "Second");
        mockMvc.perform(put("/api/trail/" + trailId + "/item/" + second)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"annotation\":\"why this step\"}"))
                .andExpect(status().isNoContent());
        publish(owner, source);

        Project fork = forkOf(forker, source);

        long forkTrailId = trailRepository.findByProjectId(fork.getId()).get(0).getId();
        List<TrailItem> steps = trailItemRepository.findByTrailIdOrderByOrderIndexAsc(forkTrailId);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getItem().getTitle()).isEqualTo("First");
        assertThat(steps.get(1).getItem().getTitle()).isEqualTo("Second");
        assertThat(steps.get(1).getAnnotation()).isEqualTo("why this step");
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

    @Test
    void forkFromSnapshotCopiesItemsThatBelongToNoTrail() throws Exception {
        User owner = createUser("fsowner6");
        User forker = createUser("fsforker6");
        Project source = createProject(owner, "Loose", "private", "A description", null);
        long trailId = createTrail(owner, source, "T");
        long filedId = createItem(owner, trailId, "Filed");
        long looseId = postForId(owner, "/api/project/" + pid(source) + "/item", """
                {"title":"Loose one"}""");
        setContent(owner, looseId, "loose body");
        tie(owner, filedId, "RELATED", "ITEM", looseId).andExpect(status().isNoContent());
        publish(owner, source);

        Project fork = forkOf(forker, source);

        List<Item> all = itemRepository.findByProjectId(fork.getId());
        assertThat(all).extracting(Item::getTitle).containsExactlyInAnyOrder("Filed", "Loose one");

        Item looseCopy = all.stream().filter(i -> i.getTitle().equals("Loose one")).findFirst().orElseThrow();
        assertThat(looseCopy.getContent().getContent()).isEqualTo("loose body");
        assertThat(looseCopy.getProject().getId()).isEqualTo(fork.getId());
        assertThat(itemsOf(fork)).extracting(Item::getTitle).containsExactly("Filed");

        Item filedCopy = all.stream().filter(i -> i.getTitle().equals("Filed")).findFirst().orElseThrow();
        assertThat(associationRepository.findBySourceItemIdIn(List.of(filedCopy.getId())))
                .singleElement()
                .satisfies(a -> assertThat(a.getTargetId()).isEqualTo(looseCopy.getId()));
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
