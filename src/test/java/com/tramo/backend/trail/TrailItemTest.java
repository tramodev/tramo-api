package com.tramo.backend.trail;

import com.jayway.jsonpath.JsonPath;
import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.trail.repository.AssociationRepository;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.upload.repository.PendingImageDeletionRepository;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TrailItemTest extends AbstractIntegrationTest {

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    TrailItemRepository trailItemRepository;

    @Autowired
    AssociationRepository itemLinkRepository;

    @Autowired
    TrailRepository trailRepository;

    @Autowired
    PendingImageDeletionRepository pendingImageDeletionRepository;

    @Autowired
    ItemService itemService;

    @Value("${app.r2.public-base-url}")
    String r2PublicBaseUrl;

    private long createTrail(User owner, Project project, String title) throws Exception {
        return postForId(owner, "/api/project/" + pid(project) + "/trail", """
                {"title":"%s"}""".formatted(title));
    }

    private long createItem(User owner, long trailId, String title) throws Exception {
        return postForId(owner, "/api/trail/" + trailId + "/item", """
                {"title":"%s"}""".formatted(title));
    }

    @Test
    void createTrailUnderOwnProject() throws Exception {
        User owner = createUser("trailmaker");
        Project project = createProject(owner, "Container", "private");

        mockMvc.perform(post("/api/project/" + pid(project) + "/trail")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"My trail"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("My trail"))
                .andExpect(jsonPath("$.projectId").value(pid(project)));
    }

    @Test
    void createTrailRequiresTitle() throws Exception {
        User owner = createUser("trailmaker2");
        Project project = createProject(owner, "Container2", "private");

        mockMvc.perform(post("/api/project/" + pid(project) + "/trail")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":""}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTrailUnderOthersProjectIsForbidden() throws Exception {
        User owner = createUser("trailowner");
        User intruder = createUser("trailintruder");
        Project project = createProject(owner, "NotYours", "private");

        mockMvc.perform(post("/api/project/" + pid(project) + "/trail")
                        .header("Authorization", bearer(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Sneaky"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void trailEndpointsEnforceOwnership() throws Exception {
        User owner = createUser("trailowner2");
        User intruder = createUser("trailintruder2");
        Project project = createProject(owner, "Guarded", "private");
        long trailId = createTrail(owner, project, "Guarded trail");

        mockMvc.perform(get("/api/trail/" + trailId).header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/trail/" + trailId)
                        .header("Authorization", bearer(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Hijacked"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/trail/" + trailId).header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/project/" + pid(project) + "/trail").header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownTrailIs404() throws Exception {
        User user = createUser("trailseeker");
        mockMvc.perform(get("/api/trail/424242").header("Authorization", bearer(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateTrailChangesTitle() throws Exception {
        User owner = createUser("trailrenamer");
        Project project = createProject(owner, "Renaming", "private");
        long trailId = createTrail(owner, project, "Before");

        mockMvc.perform(put("/api/trail/" + trailId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"After"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("After"));
    }

    @Test
    void deleteTrailUnfilesItemsThatHaveAProject() throws Exception {
        User owner = createUser("traildeleter");
        Project project = createProject(owner, "Cleanup", "private");
        long trailId = createTrail(owner, project, "Doomed");
        long itemId = createItem(owner, trailId, "Orphan-to-be");

        mockMvc.perform(delete("/api/trail/" + trailId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        // The item belongs to the project, so deleting its only trail keeps it —
        // it surfaces in Unfiled rather than being destroyed.
        assertThat(itemRepository.findById(itemId)).isPresent();
        assertThat(itemRepository.findById(itemId).orElseThrow().getUnfiled()).isTrue();
        assertThat(trailItemRepository.findByItemId(itemId)).isEmpty();
    }

    @Test
    void sharedItemSurvivesSingleTrailDelete() throws Exception {
        User owner = createUser("sharer");
        Project project = createProject(owner, "Shared", "private");
        long trail1 = createTrail(owner, project, "First");
        long trail2 = createTrail(owner, project, "Second");
        long itemId = createItem(owner, trail1, "Shared item");

        mockMvc.perform(post("/api/trail/" + trail2 + "/item/" + itemId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/trail/" + trail1).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        assertThat(itemRepository.findById(itemId)).isPresent();
        assertThat(trailItemRepository.findByItemId(itemId)).hasSize(1);
    }

    @Test
    void createItemRequiresTitle() throws Exception {
        User owner = createUser("itemmaker");
        Project project = createProject(owner, "Items", "private");
        long trailId = createTrail(owner, project, "Items trail");

        mockMvc.perform(post("/api/trail/" + trailId + "/item")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":" "}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void itemContentRoundtrip() throws Exception {
        User owner = createUser("writer");
        Project project = createProject(owner, "Writing", "private");
        long trailId = createTrail(owner, project, "Writing trail");
        long itemId = createItem(owner, trailId, "Draft");

        mockMvc.perform(get("/api/item/" + itemId + "/content").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(""));

        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"{\\"root\\":{\\"children\\":[]}}"}"""))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/item/" + itemId + "/content").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("{\"root\":{\"children\":[]}}"));
    }

    @Test
    void itemEndpointsEnforceOwnership() throws Exception {
        User owner = createUser("itemowner");
        User intruder = createUser("itemintruder");
        Project project = createProject(owner, "Locked", "private");
        long trailId = createTrail(owner, project, "Locked trail");
        long itemId = createItem(owner, trailId, "Locked item");

        mockMvc.perform(get("/api/item/" + itemId + "/content").header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"stolen"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/item/" + itemId)
                        .header("Authorization", bearer(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Stolen"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/item/" + itemId).header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateItemChangesTitleAndType() throws Exception {
        User owner = createUser("itemeditor");
        Project project = createProject(owner, "Editing", "private");
        long trailId = createTrail(owner, project, "Editing trail");
        long itemId = createItem(owner, trailId, "Plain");

        mockMvc.perform(put("/api/item/" + itemId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Renamed","type":"video"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed"))
                .andExpect(jsonPath("$.type").value("video"));
    }

    @Test
    void deleteItemRemovesIt() throws Exception {
        User owner = createUser("itemdeleter");
        Project project = createProject(owner, "Deleting", "private");
        long trailId = createTrail(owner, project, "Deleting trail");
        long itemId = createItem(owner, trailId, "Doomed item");

        mockMvc.perform(delete("/api/item/" + itemId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        assertThat(itemRepository.findById(itemId)).isEmpty();
        assertThat(trailItemRepository.findByItemId(itemId)).isEmpty();
    }

    @Test
    void attachIsIdempotentAndDetachUnfilesLastCopy() throws Exception {
        User owner = createUser("attacher");
        Project project = createProject(owner, "Attaching", "private");
        long trail1 = createTrail(owner, project, "P1");
        long trail2 = createTrail(owner, project, "P2");
        long itemId = createItem(owner, trail1, "Movable");

        mockMvc.perform(post("/api/trail/" + trail2 + "/item/" + itemId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/trail/" + trail2 + "/item/" + itemId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(trailItemRepository.findByItemId(itemId)).hasSize(2);

        mockMvc.perform(delete("/api/trail/" + trail1 + "/item/" + itemId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(trailItemRepository.findByItemId(itemId)).hasSize(1);
        assertThat(itemRepository.findById(itemId)).isPresent();

        // Detaching the last trail keeps the project-owned item and marks it Unfiled.
        mockMvc.perform(delete("/api/trail/" + trail2 + "/item/" + itemId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(trailItemRepository.findByItemId(itemId)).isEmpty();
        assertThat(itemRepository.findById(itemId)).isPresent();
        assertThat(itemRepository.findById(itemId).orElseThrow().getUnfiled()).isTrue();
    }

    private ResultActions tie(User owner, long sourceItem, String type, String targetType, long targetId) throws Exception {
        return mockMvc.perform(post("/api/item/" + sourceItem + "/tie")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"" + type + "\",\"targetType\":\"" + targetType + "\",\"targetId\":" + targetId + "}"));
    }

    @Test
    void tyingItemsIsDirectionalAndIdempotent() throws Exception {
        User owner = createUser("linker");
        Project project = createProject(owner, "Linking", "private");
        long trailId = createTrail(owner, project, "Linking trail");
        long itemA = createItem(owner, trailId, "A");
        long itemB = createItem(owner, trailId, "B");

        tie(owner, itemA, "RELATED", "ITEM", itemB).andExpect(status().isNoContent());
        tie(owner, itemA, "RELATED", "ITEM", itemB).andExpect(status().isNoContent()); // idempotent

        assertThat(itemLinkRepository.count()).isEqualTo(1);

        mockMvc.perform(get("/api/item/" + itemA + "/association").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].targetType").value("ITEM"))
                .andExpect(jsonPath("$[0].type").value("RELATED"))
                .andExpect(jsonPath("$[0].targetTitle").value("B"));

        // directional: B has no outgoing association back to A
        mockMvc.perform(get("/api/item/" + itemB + "/association").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void canTieItemToTrail() throws Exception {
        User owner = createUser("trailtier");
        Project project = createProject(owner, "TrailTie", "private");
        long trailId = createTrail(owner, project, "Target trail");
        long otherTrail = createTrail(owner, project, "Source trail");
        long itemId = createItem(owner, otherTrail, "Pointer");

        tie(owner, itemId, "ELABORATES", "TRAIL", trailId).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/item/" + itemId + "/association").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].targetType").value("TRAIL"))
                .andExpect(jsonPath("$[0].type").value("ELABORATES"))
                .andExpect(jsonPath("$[0].targetTitle").value("Target trail"));
    }

    @Test
    void selfTieIsRejected() throws Exception {
        User owner = createUser("selflinker");
        Project project = createProject(owner, "Selfish", "private");
        long trailId = createTrail(owner, project, "Selfish trail");
        long itemId = createItem(owner, trailId, "Alone");

        tie(owner, itemId, "RELATED", "ITEM", itemId).andExpect(status().isBadRequest());
    }

    @Test
    void untieRemovesAssociation() throws Exception {
        User owner = createUser("unlinker");
        Project project = createProject(owner, "Unlinking", "private");
        long trailId = createTrail(owner, project, "Unlinking trail");
        long itemA = createItem(owner, trailId, "A");
        long itemB = createItem(owner, trailId, "B");

        tie(owner, itemA, "RELATED", "ITEM", itemB).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/item/" + itemA + "/tie?targetType=ITEM&targetId=" + itemB)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        assertThat(itemLinkRepository.count()).isZero();
    }

    @Test
    void cannotTieToAnotherUsersItem() throws Exception {
        User owner = createUser("linkowner");
        User other = createUser("linkother");
        Project mine = createProject(owner, "MineL", "private");
        Project theirs = createProject(other, "TheirsL", "private");
        long myTrail = createTrail(owner, mine, "My trail");
        long theirTrail = createTrail(other, theirs, "Their trail");
        long myItem = createItem(owner, myTrail, "My item");
        long theirItem = createItem(other, theirTrail, "Their item");

        tie(owner, myItem, "RELATED", "ITEM", theirItem).andExpect(status().isForbidden());
    }

    @Test
    void forkCopiesItemAssociations() throws Exception {
        User owner = createUser("forkorigin");
        User forker = createUser("forkuser");
        Project source = createProject(owner, "Forkable", "published");
        long trailId = createTrail(owner, source, "T");
        long itemA = createItem(owner, trailId, "A");
        long itemB = createItem(owner, trailId, "B");
        tie(owner, itemA, "REQUIRES", "ITEM", itemB).andExpect(status().isNoContent());

        assertThat(itemLinkRepository.count()).isEqualTo(1);
        postForProjectId(forker, "/api/project/" + pid(source) + "/fork", "");
        // the fork snapshot duplicates the association onto the copied items
        assertThat(itemLinkRepository.count()).isEqualTo(2);
    }

    private ResultActions blaze(User owner, long trailId, long itemId, String jsonBody) throws Exception {
        return mockMvc.perform(put("/api/trail/" + trailId + "/item/" + itemId)
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody));
    }

    @Test
    void blazeSetsStepAnnotation() throws Exception {
        User owner = createUser("blazer");
        Project project = createProject(owner, "Blaze", "private");
        long trailId = createTrail(owner, project, "T");
        long itemId = createItem(owner, trailId, "Step");

        blaze(owner, trailId, itemId, "{\"annotation\":\"because it follows\"}")
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/trail/" + trailId + "/item").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].annotation").value("because it follows"));
    }

    @Test
    void blazeSetsStepAssociation() throws Exception {
        User owner = createUser("blazer2");
        Project project = createProject(owner, "Blaze2", "private");
        long trailId = createTrail(owner, project, "T");
        long itemA = createItem(owner, trailId, "A");
        long itemB = createItem(owner, trailId, "B");
        tie(owner, itemA, "REQUIRES", "ITEM", itemB).andExpect(status().isNoContent());

        String assocResponse = mockMvc.perform(get("/api/item/" + itemA + "/association")
                        .header("Authorization", bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        String assocId = JsonPath.read(assocResponse, "$[0].id");

        // mark that item B was reached via that association
        blaze(owner, trailId, itemB, "{\"associationId\":" + assocId + "}")
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/trail/" + trailId + "/item").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].associationId").value(assocId));
    }

    @Test
    void publishBumpsTrailVersion() throws Exception {
        User owner = createUser("publisher");
        Project project = createProject(owner, "Pub", "private", "A description", null);
        long trailId = createTrail(owner, project, "T");

        mockMvc.perform(get("/api/trail/" + trailId).header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(put("/api/project/" + pid(project))
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"published\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/trail/" + trailId).header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void forkCopiesAnnotationsAndForkedFrom() throws Exception {
        User owner = createUser("forkann");
        User forker = createUser("forkann2");
        Project source = createProject(owner, "Forkable", "published");
        long trailId = createTrail(owner, source, "T");
        long itemId = createItem(owner, trailId, "Step");
        blaze(owner, trailId, itemId, "{\"annotation\":\"copied note\"}")
                .andExpect(status().isNoContent());

        String forkId = postForProjectId(forker, "/api/project/" + pid(source) + "/fork", "");
        Project fork = projectRepository.findById(projectIdCodec.decode(forkId)).orElseThrow();
        Trail forkedTrail = trailRepository.findByProjectId(fork.getId()).get(0);

        assertThat(forkedTrail.getForkedFrom()).isNotNull();
        assertThat(trailItemRepository.findByTrailIdOrderByOrderIndexAsc(forkedTrail.getId()).get(0).getAnnotation())
                .isEqualTo("copied note");
    }

    @Test
    void listTrailsForProjectReturnsAllOfThem() throws Exception {
        User owner = createUser("traillister");
        Project project = createProject(owner, "Listable", "private");
        createTrail(owner, project, "First");
        createTrail(owner, project, "Second");

        mockMvc.perform(get("/api/project/" + pid(project) + "/trail").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void updateTrailChangesTitleAndVisibility() throws Exception {
        User owner = createUser("trailupdater");
        Project project = createProject(owner, "Updatable", "private");
        long trailId = createTrail(owner, project, "Original");

        mockMvc.perform(put("/api/trail/" + trailId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Renamed","visibility":"public"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed"));

        assertThat(trailRepository.findById(trailId).orElseThrow().getVisibility()).isEqualTo("public");
    }

    @Test
    void listItemsForTrailReturnsAllOfThem() throws Exception {
        User owner = createUser("itemlister");
        Project project = createProject(owner, "ItemContainer", "private");
        long trailId = createTrail(owner, project, "Trail with items");
        createItem(owner, trailId, "Item one");
        createItem(owner, trailId, "Item two");

        mockMvc.perform(get("/api/trail/" + trailId + "/item").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void createLooseItemIsUnfiledAndSurfacesInProjectItems() throws Exception {
        User owner = createUser("looser");
        Project project = createProject(owner, "Loose", "private");

        mockMvc.perform(post("/api/project/" + pid(project) + "/item")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Floating"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Floating"))
                .andExpect(jsonPath("$.unfiled").value(true));

        // Both trail-bound and loose items belong to the project and are listed together.
        long trailId = createTrail(owner, project, "T");
        createItem(owner, trailId, "In a trail");

        mockMvc.perform(get("/api/project/" + pid(project) + "/item").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void createLooseRequiresTitle() throws Exception {
        User owner = createUser("looser2");
        Project project = createProject(owner, "Loose2", "private");

        mockMvc.perform(post("/api/project/" + pid(project) + "/item")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":" "}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void projectItemEndpointsEnforceOwnership() throws Exception {
        User owner = createUser("projitemowner");
        User intruder = createUser("projitemintruder");
        Project project = createProject(owner, "Guarded", "private");

        mockMvc.perform(post("/api/project/" + pid(project) + "/item")
                        .header("Authorization", bearer(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Sneaky"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/project/" + pid(project) + "/item").header("Authorization", bearer(intruder)))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateContentCreatesContentWhenNoneExistsYet() throws Exception {
        User owner = createUser("contentcreator");
        Project project = createProject(owner, "ContentContainer", "private");
        long trailId = createTrail(owner, project, "Trail");
        long itemId = createItem(owner, trailId, "Fresh item");

        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Hello world"}"""))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/item/" + itemId + "/content").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Hello world"));
    }

    @Test
    void updateContentBumpsDashboardLastModifiedButNotExploreModifiedDate() throws Exception {
        User owner = createUser("contentmodifier");
        Project project = createProject(owner, "ModifiedDateProject", "private");
        long trailId = createTrail(owner, project, "Trail");
        long itemId = createItem(owner, trailId, "Item");

        Date staleDate = new Date(System.currentTimeMillis() - 60_000);
        project.setModifiedDate(staleDate);
        projectRepository.save(project);

        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Updated"}"""))
                .andExpect(status().isNoContent());

        Project reloaded = projectRepository.findById(project.getId()).orElseThrow();
        assertThat(reloaded.getModifiedDate().getTime()).isEqualTo(staleDate.getTime());
        assertThat(reloaded.getLastEditedDate()).isAfter(staleDate);

        String response = mockMvc.perform(get("/api/project/" + pid(project)).header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Object modifiedDate = JsonPath.read(response, "$.modifiedDate");
        Instant afterEdit = modifiedDate instanceof Number
                ? Instant.ofEpochMilli(((Number) modifiedDate).longValue())
                : Instant.parse((String) modifiedDate);
        assertThat(afterEdit).isAfter(staleDate.toInstant());
    }

    @Test
    void removingEditorImageFromContentDoesNotBreakSave() throws Exception {
        User owner = createUser("imagecleaner1");
        Project project = createProject(owner, "ImageCleanupProject", "private");
        long trailId = createTrail(owner, project, "Trail");
        long itemId = createItem(owner, trailId, "Item with image");
        String imageUrl = r2PublicBaseUrl + "/editor-image/999999/deadbeefcafefeed.jpg";

        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"has image %s here"}""".formatted(imageUrl)))
                .andExpect(status().isNoContent());

        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"image removed now"}"""))
                .andExpect(status().isNoContent());

        assertThat(pendingImageDeletionRepository.existsByUrl(imageUrl)).isTrue();
    }

    @Test
    void purgeDeletesOnlyStaleUnreferencedPendingImages() throws Exception {
        User owner = createUser("imagepurger");
        Project project = createProject(owner, "PurgeProject", "private");
        long trailId = createTrail(owner, project, "Trail");
        long itemId = createItem(owner, trailId, "Item");
        String staleUrl = r2PublicBaseUrl + "/editor-image/999999/stalehash.jpg";
        String restoredUrl = r2PublicBaseUrl + "/editor-image/999999/restoredhash.jpg";
        String freshUrl = r2PublicBaseUrl + "/editor-image/999999/freshhash.jpg";

        for (String url : new String[]{staleUrl, restoredUrl, freshUrl}) {
            mockMvc.perform(put("/api/item/" + itemId + "/content")
                            .header("Authorization", bearer(owner))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"content":"has %s"}""".formatted(url)))
                    .andExpect(status().isNoContent());
            mockMvc.perform(put("/api/item/" + itemId + "/content")
                            .header("Authorization", bearer(owner))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"content":"removed"}"""))
                    .andExpect(status().isNoContent());
        }
        assertThat(pendingImageDeletionRepository.count()).isEqualTo(3);

        Date twoDaysAgo = new Date(System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L);
        jdbcTemplate.update("UPDATE pending_image_deletion SET requested_at = ? WHERE url IN (?, ?)",
                twoDaysAgo, staleUrl, restoredUrl);

        mockMvc.perform(put("/api/item/" + itemId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"pasted back %s"}""".formatted(restoredUrl)))
                .andExpect(status().isNoContent());

        itemService.purgePendingImageDeletions();

        assertThat(pendingImageDeletionRepository.existsByUrl(staleUrl)).isFalse();
        assertThat(pendingImageDeletionRepository.existsByUrl(restoredUrl)).isFalse();
        assertThat(pendingImageDeletionRepository.existsByUrl(freshUrl)).isTrue();
        assertThat(trailItemRepository.existsOtherItemReferencingUrl(owner.getId(), restoredUrl, -1L)).isTrue();
    }

    @Test
    void existsOtherItemReferencingUrlDetectsSharedImageCorrectly() throws Exception {
        User owner = createUser("imagecleaner2");
        Project project = createProject(owner, "SharedImageProject", "private");
        long trailId = createTrail(owner, project, "Trail");
        long itemAId = createItem(owner, trailId, "Item A");
        long itemBId = createItem(owner, trailId, "Item B");
        String sharedUrl = r2PublicBaseUrl + "/editor-image/999999/sharedhash.jpg";
        String contentWithImage = """
                {"content":"shared %s here"}""".formatted(sharedUrl);

        mockMvc.perform(put("/api/item/" + itemAId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contentWithImage))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/api/item/" + itemBId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contentWithImage))
                .andExpect(status().isNoContent());

        assertThat(trailItemRepository.existsOtherItemReferencingUrl(owner.getId(), sharedUrl, itemAId)).isTrue();

        mockMvc.perform(put("/api/item/" + itemAId + "/content")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"image removed from A"}"""))
                .andExpect(status().isNoContent());

        assertThat(trailItemRepository.existsOtherItemReferencingUrl(owner.getId(), sharedUrl, itemAId)).isTrue();
        assertThat(trailItemRepository.existsOtherItemReferencingUrl(owner.getId(), sharedUrl, itemBId)).isFalse();
        assertThat(pendingImageDeletionRepository.existsByUrl(sharedUrl)).isFalse();
    }
}
