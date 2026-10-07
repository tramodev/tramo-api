package com.tramo.backend.trail;

import com.jayway.jsonpath.JsonPath;
import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.trail.entity.AssociationTargetType;
import com.tramo.backend.trail.repository.AssociationRepository;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.upload.entity.UploadRecord;
import com.tramo.backend.upload.repository.EditorImageRepository;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.upload.service.EditorImageService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IndependentItemCopyTest extends AbstractIntegrationTest {
    @Autowired ItemRepository items;
    @Autowired TrailItemRepository steps;
    @Autowired AssociationRepository associations;
    @Autowired ItemService itemService;
    @Autowired EditorImageRepository imageRepository;
    @Autowired UploadRecordRepository uploads;
    @MockitoSpyBean EditorImageService images;
    private final ObjectMapper mapper = new ObjectMapper();

    record Fixture(User owner, Project project, long first, long second, long third, long before, long shared, long after) {}

    private Fixture fixture() throws Exception {
        User owner = createUser("copyowner");
        Project project = createProject(owner, "Copy project", "PRIVATE");
        long first = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"First\"}");
        long second = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Second\"}");
        long third = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Third\"}");
        long before = postForId(owner, "/api/trail/" + first + "/item", "{\"title\":\"Before\"}");
        long shared = postForId(owner, "/api/trail/" + first + "/item", "{\"title\":\"Shared\"}");
        long after = postForId(owner, "/api/trail/" + first + "/item", "{\"title\":\"After\"}");
        for (long trail : List.of(second, third)) {
            mockMvc.perform(post("/api/trail/" + trail + "/item/" + shared).header("Authorization", bearer(owner)))
                    .andExpect(status().isNoContent());
        }
        mockMvc.perform(put("/api/item/" + shared).header("Authorization", bearer(owner)).contentType("application/json")
                .content("{\"titleAlign\":\"right\"}")).andExpect(status().isOk());
        itemService.updateContent(shared, "{\"root\":{\"children\":[{\"type\":\"link\",\"url\":\"/item/" + before + "\",\"children\":[{\"type\":\"text\",\"text\":\"Shared content\"}]}]}}", owner);
        return new Fixture(owner, project, first, second, third, before, shared, after);
    }

    private String copy(Fixture f) throws Exception {
        return mockMvc.perform(post("/api/trail/" + f.first + "/item/" + f.shared + "/independent-copy")
                        .header("Authorization", bearer(f.owner))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void copyReplacesOneStepAndKeepsContentMetadataAndConnectionsIndependent() throws Exception {
        Fixture f = fixture();
        mockMvc.perform(post("/api/item/" + f.before + "/tie").header("Authorization", bearer(f.owner))
                .contentType("application/json").content("{\"type\":\"RELATED\",\"targetType\":\"ITEM\",\"targetId\":" + f.shared + "}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/item/" + f.shared + "/tie").header("Authorization", bearer(f.owner))
                .contentType("application/json").content("{\"type\":\"REQUIRES\",\"targetType\":\"ITEM\",\"targetId\":" + f.after + "}"))
                .andExpect(status().isNoContent());
        long incoming = associations.findBySourceItemId(f.before).get(0).getId();
        long outgoing = associations.findBySourceItemId(f.shared).get(0).getId();
        itemService.updateStep(f.first, f.shared, "Keep this annotation", incoming, f.owner);
        itemService.updateStep(f.first, f.after, "Next annotation", outgoing, f.owner);
        long stepId = steps.findByTrailIdAndItemId(f.first, f.shared).orElseThrow().getId();
        String content = itemService.getContent(f.shared, f.owner).getContent();
        String response = copy(f);
        long copyId = ((Number) JsonPath.read(response, "$.item.id")).longValue();
        assertThat(copyId).isNotEqualTo(f.shared);
        assertThat((String) JsonPath.read(response, "$.content")).isEqualTo(content);
        assertThat((String) JsonPath.read(response, "$.item.titleAlign")).isEqualTo("right");
        assertThat((String) JsonPath.read(response, "$.item.title")).isEqualTo("Shared");
        var copiedStep = steps.findByTrailIdAndItemId(f.first, copyId).orElseThrow();
        assertThat(copiedStep.getId()).isEqualTo(stepId);
        assertThat(copiedStep.getOrderIndex()).isEqualTo(1);
        assertThat(copiedStep.getAnnotation()).isEqualTo("Keep this annotation");
        assertThat(copiedStep.getAssociation()).isNull();
        assertThat(steps.findByItemId(f.shared)).extracting(step -> step.getTrail().getId()).containsExactlyInAnyOrder(f.second, f.third);
        var copyLinks = associations.findBySourceItemId(copyId);
        assertThat(copyLinks).hasSize(1);
        assertThat(copyLinks.get(0).getId()).isNotEqualTo(outgoing);
        assertThat(copyLinks.get(0).getTargetId()).isEqualTo(f.after);
        assertThat(steps.findByTrailIdAndItemId(f.first, f.after).orElseThrow().getAssociation().getId()).isEqualTo(copyLinks.get(0).getId());
        assertThat(associations.findByTargetTypeAndTargetId(AssociationTargetType.ITEM, copyId)).isEmpty();
        assertThat(associations.findByTargetTypeAndTargetId(AssociationTargetType.ITEM, f.shared)).hasSize(1);
        itemService.updateContent(copyId, "{\"root\":{\"children\":[{\"type\":\"text\",\"text\":\"Independent\"}]}}", f.owner);
        assertThat(itemService.getContent(f.shared, f.owner).getContent()).isEqualTo(content);
        assertThat(items.findById(copyId).orElseThrow().getContent().getId()).isNotEqualTo(items.findById(f.shared).orElseThrow().getContent().getId());
    }

    @Test
    void rejectsUnauthorizedMissingMembershipUnsharedNotesAndRepeatedRequests() throws Exception {
        Fixture f = fixture();
        User stranger = createUser("stranger");
        mockMvc.perform(post("/api/trail/" + f.first + "/item/" + f.shared + "/independent-copy")
                .header("Authorization", bearer(stranger))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/trail/" + f.second + "/item/" + f.before + "/independent-copy")
                .header("Authorization", bearer(f.owner))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/trail/" + f.first + "/item/" + f.before + "/independent-copy")
                .header("Authorization", bearer(f.owner))).andExpect(status().isBadRequest());
        long count = items.count();
        copy(f);
        mockMvc.perform(post("/api/trail/" + f.first + "/item/" + f.shared + "/independent-copy")
                .header("Authorization", bearer(f.owner))).andExpect(status().isNotFound());
        assertThat(items.count()).isEqualTo(count + 1);
    }

    @Test
    void membershipsIncludeOtherProjectsWithoutAdditionalQueriesPerTrail() throws Exception {
        Fixture f = fixture();
        Project other = createProject(f.owner, "Other project", "PRIVATE");
        long remote = postForId(f.owner, "/api/project/" + pid(other) + "/trail", "{\"title\":\"Remote trail\"}");
        itemService.attachToTrail(remote, f.shared, f.owner);
        String path = "/api/project/" + pid(f.project) + "/item-trails";
        mockMvc.perform(get(path).header("Authorization", bearer(f.owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.itemId == " + f.shared + ")]").value(org.hamcrest.Matchers.hasSize(4)))
                .andExpect(jsonPath("$[?(@.trailId == " + remote + ")].projectId").value(org.hamcrest.Matchers.contains(pid(other))));
        long baseline = queryCount(() -> mockMvc.perform(get(path).header("Authorization", bearer(f.owner))).andExpect(status().isOk()));
        for (int i = 0; i < 5; i++) {
            long trail = postForId(f.owner, "/api/project/" + pid(f.project) + "/trail", "{\"title\":\"Extra\"}");
            itemService.attachToTrail(trail, f.before, f.owner);
        }
        assertThat(queryCount(() -> mockMvc.perform(get(path).header("Authorization", bearer(f.owner))).andExpect(status().isOk())))
                .isEqualTo(baseline);
        mockMvc.perform(get(path).header("Authorization", bearer(createUser("unauthorized")))).andExpect(status().isForbidden());
        var response = itemService.copyForTrail(remote, f.shared, f.owner);
        assertThat(items.findById(response.item().getId()).orElseThrow().getProject().getId()).isEqualTo(other.getId());
        assertThat(steps.findByItemId(f.shared)).hasSize(3);
    }

    @Test
    void concurrentCopyRequestsCreateOnlyOneItem() throws Exception {
        Fixture f = fixture();
        long count = items.count();
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Boolean> request = () -> {
            start.await();
            try {
                itemService.copyForTrail(f.first, f.shared, f.owner);
                return true;
            } catch (com.tramo.backend.exception.ResourceNotFoundException expected) {
                return false;
            }
        };
        try {
            var first = pool.submit(request);
            var second = pool.submit(request);
            start.countDown();
            assertThat(List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS), second.get(15, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(items.count()).isEqualTo(count + 1);
            assertThat(steps.findByItemId(f.shared)).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void imageReferenceFailureRollsBackCreationAndReplacement() throws Exception {
        Fixture f = fixture();
        itemService.updateStep(f.first, f.shared, "Preserved", null, f.owner);
        long itemCount = items.count();
        long contentCount = jdbcTemplate.queryForObject("SELECT count(*) FROM item_content", Long.class);
        String content = itemService.getContent(f.shared, f.owner).getContent();
        doThrow(new IllegalStateException("Image unavailable")).when(images).copyItemReferences(any(), any(), any());
        mockMvc.perform(post("/api/trail/" + f.first + "/item/" + f.shared + "/independent-copy")
                .header("Authorization", bearer(f.owner))).andExpect(status().isInternalServerError());
        assertThat(items.count()).isEqualTo(itemCount);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM item_content", Long.class)).isEqualTo(contentCount);
        assertThat(steps.findByItemId(f.shared)).hasSize(3);
        assertThat(steps.findByTrailIdAndItemId(f.first, f.shared).orElseThrow().getAnnotation()).isEqualTo("Preserved");
        assertThat(itemService.getContent(f.shared, f.owner).getContent()).isEqualTo(content);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copiedPrivateImageRemainsReadableAfterDeletingOriginal(boolean retainedFromFork) throws Exception {
        Fixture f = fixture();
        Project imageProject = retainedFromFork ? createProject(createUser("imageowner"), "Source", "PRIVATE") : f.project;
        UUID imageId = UUID.randomUUID();
        UploadRecord upload = new UploadRecord();
        upload.setUserId(f.owner.getId());
        upload.setProjectId(imageProject.getId());
        upload.setObjectKey("private/" + imageId);
        upload.setBytes(100L);
        upload.setCreatedDate(new Date());
        upload = uploads.saveAndFlush(upload);
        imageRepository.createObject(imageId, "image/jpeg", 100L, "a".repeat(64));
        imageRepository.createImage(imageId, imageId, imageProject.getId(), upload.getId());
        if (retainedFromFork) imageRepository.replaceItemReferences(f.shared, List.of(imageId));
        imageRepository.setState(imageId, "READY");
        String content = "{\"root\":{\"children\":[{\"type\":\"image\",\"version\":2,\"imageId\":\"" + imageId + "\"}]}}";
        itemService.updateContent(f.shared, content, f.owner);
        long copyId = ((Number) JsonPath.read(copy(f), "$.item.id")).longValue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM editor_image_item_reference WHERE image_id = ?", Long.class, imageId)).isEqualTo(2L);
        itemService.delete(f.shared, f.owner);
        jdbcTemplate.update("UPDATE editor_image SET last_used_at = CURRENT_TIMESTAMP - INTERVAL '2 days'");
        jdbcTemplate.update("UPDATE editor_image_object SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 days'");
        images.purge();
        assertThat(imageRepository.findAll(List.of(imageId))).hasSize(1);
        assertThat(itemService.getContent(copyId, f.owner).getContent()).isEqualTo(content);
        mockMvc.perform(post("/api/project/" + pid(f.project) + "/editor-images/resolve").header("Authorization", bearer(f.owner))
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("imageIds", List.of(imageId)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.images[0].imageId").value(imageId.toString()));
    }
}
