package com.tramo.backend.trail;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.trail.dto.*;
import com.tramo.backend.trail.service.*;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExtractSelectionTest extends AbstractIntegrationTest {
    @Autowired ItemService items;
    @Autowired ItemRepository repository;
    @Autowired ExtractSelectionService extracts;
    @Autowired com.tramo.backend.upload.repository.EditorImageRepository images;
    @Autowired com.tramo.backend.upload.repository.UploadRecordRepository uploads;
    private final ObjectMapper mapper = new ObjectMapper();
    record Fixture(User user, Project project, long trail, long other, long source, long last) {}
    static final String ORIGINAL = "{\"root\":{\"type\":\"root\",\"children\":[{\"type\":\"paragraph\",\"children\":[{\"type\":\"text\",\"text\":\"before selected after\",\"format\":3}]}]}}";
    static final String EXTRACTED = "{\"root\":{\"type\":\"root\",\"children\":[{\"type\":\"paragraph\",\"children\":[{\"type\":\"text\",\"text\":\"selected\",\"format\":3}]}]}}";
    Fixture fixture() throws Exception {
        User user = createUser("extractowner"); Project project = createProject(user, "Notes", "PRIVATE");
        long trail = postForId(user, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Current\"}");
        long other = postForId(user, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Other\"}");
        long source = postForId(user, "/api/trail/" + trail + "/item", "{\"title\":\"Source\"}");
        long last = postForId(user, "/api/trail/" + trail + "/item", "{\"title\":\"Last\"}");
        items.attachToTrail(other, source, user); items.updateContent(source, ORIGINAL, user);
        mockMvc.perform(post("/api/item/" + source + "/tie").header("Authorization", bearer(user)).contentType("application/json").content("{\"type\":\"RELATED\",\"targetType\":\"ITEM\",\"targetId\":" + last + "}")).andExpect(status().isNoContent());
        Long association = jdbcTemplate.queryForObject("SELECT id FROM association WHERE source_item_id = ?", Long.class, source);
        items.updateStep(trail, last, "Keep this annotation", association, user);
        return new Fixture(user, project, trail, other, source, last);
    }
    ExtractSelectionRequest request(Fixture f, boolean step, UUID id) {
        String replacement = "{\"root\":{\"type\":\"root\",\"children\":[{\"type\":\"paragraph\",\"children\":[{\"type\":\"text\",\"text\":\"before \"},{\"type\":\"link\",\"url\":\"#\",\"rel\":\"tramo-extraction:" + id + "\",\"children\":[{\"type\":\"text\",\"text\":\"Extracted\"}]},{\"type\":\"text\",\"text\":\" after\"}]}]}}";
        return new ExtractSelectionRequest(id, "Extracted", ORIGINAL, 0, replacement, EXTRACTED, step ? f.trail : null, step ? List.of(f.source, f.last) : null);
    }
    JsonNode send(Fixture f, ExtractSelectionRequest request) throws Exception {
        String response = mockMvc.perform(post("/api/project/" + pid(f.project) + "/item/" + f.source + "/extract").header("Authorization", bearer(f.user)).contentType("application/json").content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }
    @Test
    void movesContentToLooseNoteKeepsSharedSourceIdentityAndRelationships() throws Exception {
        Fixture f = fixture(); long count = repository.count();
        JsonNode result = send(f, request(f, false, UUID.randomUUID())); long id = result.path("item").path("id").asLong();
        assertThat(repository.count()).isEqualTo(count + 1);
        assertThat(result.path("item").path("unfiled").asBoolean()).isTrue();
        assertThat(items.getContent(id, f.user).getContent()).isEqualTo(EXTRACTED);
        assertThat(items.getContent(f.source, f.user).getContent()).contains("tramo-idea:" + id, "before ", " after").doesNotContain("selected");
        assertThat(items.getAllForTrail(f.trail, f.user)).extracting(TrailItemDTO::id).containsExactly(f.source, f.last);
        assertThat(items.getAllForTrail(f.other, f.user)).extracting(TrailItemDTO::id).containsExactly(f.source);
        assertThat(items.getAssociations(f.source, f.user)).hasSize(1); assertThat(items.getAssociations(id, f.user)).isEmpty();
        items.updateContent(id, "", f.user); items.delete(id, f.user);
        assertThat(items.getContent(f.source, f.user).getContent()).contains("tramo-idea:" + id);
        assertThat(repository.findById(f.source)).isPresent();
    }
    @Test
    void insertsImmediatelyAfterSourceOnlyInCurrentTrailAndPreservesExistingStepData() throws Exception {
        Fixture f = fixture(); var previous = items.getAllForTrail(f.trail, f.user).get(1);
        JsonNode result = send(f, request(f, true, UUID.randomUUID())); long id = result.path("item").path("id").asLong();
        var steps = items.getAllForTrail(f.trail, f.user);
        assertThat(steps).extracting(TrailItemDTO::id).containsExactly(f.source, id, f.last);
        assertThat(steps.get(1).annotation()).isNull(); assertThat(steps.get(1).associationId()).isNull();
        assertThat(steps.get(2).annotation()).isEqualTo(previous.annotation()); assertThat(steps.get(2).associationId()).isEqualTo(previous.associationId());
        assertThat(items.getAllForTrail(f.other, f.user)).hasSize(1);
        assertThat(result.path("item").path("unfiled").asBoolean()).isFalse();
    }
    @Test
    void retriesAndConcurrentDoubleSubmissionCreateExactlyOneNoteAndOneStep() throws Exception {
        Fixture f = fixture(); long count = repository.count(); var request = request(f, true, UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> extracts.extract(f.project.getId(), f.source, request, f.user));
            var second = pool.submit(() -> extracts.extract(f.project.getId(), f.source, request, f.user));
            assertThat(first.get(20, TimeUnit.SECONDS).item().getId()).isEqualTo(second.get(20, TimeUnit.SECONDS).item().getId());
        } finally { pool.shutdownNow(); }
        send(f, request);
        assertThat(repository.count()).isEqualTo(count + 1); assertThat(items.getAllForTrail(f.trail, f.user)).hasSize(3);
    }
    @Test
    void rejectsStaleSourceAndTrailWithoutCreatingAnything() throws Exception {
        Fixture f = fixture(); var request = request(f, true, UUID.randomUUID()); long count = repository.count();
        items.reorderTrailItems(f.trail, List.of(f.last, f.source), f.user);
        mockMvc.perform(post("/api/project/" + pid(f.project) + "/item/" + f.source + "/extract").header("Authorization", bearer(f.user)).contentType("application/json").content(mapper.writeValueAsString(request))).andExpect(status().isConflict());
        items.updateContent(f.source, "changed", f.user);
        mockMvc.perform(post("/api/project/" + pid(f.project) + "/item/" + f.source + "/extract").header("Authorization", bearer(f.user)).contentType("application/json").content(mapper.writeValueAsString(request))).andExpect(status().isConflict());
        assertThat(repository.count()).isEqualTo(count); assertThat(items.getContent(f.source, f.user).getContent()).isEqualTo("changed");
    }
    @Test
    void rejectsForeignUserAndProjectAndUnsupportedSelection() throws Exception {
        Fixture f = fixture(); var request = request(f, false, UUID.randomUUID()); long count = repository.count();
        User stranger = createUser("extractstranger");
        mockMvc.perform(post("/api/project/" + pid(f.project) + "/item/" + f.source + "/extract").header("Authorization", bearer(stranger)).contentType("application/json").content(mapper.writeValueAsString(request))).andExpect(status().isForbidden());
        Project elsewhere = createProject(f.user, "Elsewhere", "PRIVATE");
        mockMvc.perform(post("/api/project/" + pid(elsewhere) + "/item/" + f.source + "/extract").header("Authorization", bearer(f.user)).contentType("application/json").content(mapper.writeValueAsString(request))).andExpect(status().isConflict());
        var unsupported = new ExtractSelectionRequest(request.operationId(), request.title(), request.expectedContent(), 0, request.sourceContent(), "{\"root\":{\"type\":\"root\",\"children\":[{\"type\":\"image\"}]}}", null, null);
        mockMvc.perform(post("/api/project/" + pid(f.project) + "/item/" + f.source + "/extract").header("Authorization", bearer(f.user)).contentType("application/json").content(mapper.writeValueAsString(unsupported))).andExpect(status().isConflict());
        assertThat(repository.count()).isEqualTo(count); assertThat(items.getContent(f.source, f.user).getContent()).isEqualTo(ORIGINAL);
    }
    @Test
    void staleAutosaveCannotRestoreExtractedContentButSubsequentEditsCanSave() throws Exception {
        Fixture f = fixture(); JsonNode result = send(f, request(f, false, UUID.randomUUID()));
        mockMvc.perform(put("/api/item/" + f.source + "/content").header("Authorization", bearer(f.user)).contentType("application/json").content(mapper.writeValueAsString(Map.of("content", ORIGINAL, "expectedContent", ORIGINAL, "extractionEpoch", 0)))).andExpect(status().isConflict());
        String confirmed = result.path("sourceContent").asText();
        mockMvc.perform(put("/api/item/" + f.source + "/content").header("Authorization", bearer(f.user)).contentType("application/json").content(mapper.writeValueAsString(Map.of("content", confirmed, "expectedContent", confirmed, "extractionEpoch", 1)))).andExpect(status().isNoContent());
        assertThat(items.getContent(f.source, f.user).getContent()).isEqualTo(confirmed);
        items.updateContent(f.source, "other tab", 1, f.user);
        mockMvc.perform(put("/api/item/" + f.source + "/content").header("Authorization", bearer(f.user)).contentType("application/json").content(mapper.writeValueAsString(Map.of("content", confirmed, "expectedContent", confirmed, "extractionEpoch", 1)))).andExpect(status().isConflict());
        assertThat(items.getContent(f.source, f.user).getContent()).isEqualTo("other tab");
    }
    @Test
    void failureAfterCreationRollsBackNewNoteAndAllSourceChanges() throws Exception {
        Fixture f = fixture(); var request = request(f, false, UUID.randomUUID()); long count = repository.count();
        JsonNode replacement = mapper.readTree(request.sourceContent());
        ((tools.jackson.databind.node.ArrayNode) replacement.path("root").path("children")).add(mapper.readTree("{\"type\":\"image\",\"version\":2,\"imageId\":\"" + UUID.randomUUID() + "\"}"));
        var missingImage = new ExtractSelectionRequest(request.operationId(), request.title(), request.expectedContent(), 0, mapper.writeValueAsString(replacement), request.extractedContent(), null, null);
        assertThatThrownBy(() -> extracts.extract(f.project.getId(), f.source, missingImage, f.user)).isInstanceOf(RuntimeException.class);
        assertThat(repository.count()).isEqualTo(count); assertThat(items.getContent(f.source, f.user).getContent()).isEqualTo(ORIGINAL);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM note_extraction", Long.class)).isZero();
    }
    @Test
    void reportsSharedUsageAcrossProjectsWithoutExposingTheirNames() throws Exception {
        Fixture f = fixture(); Project elsewhere = createProject(f.user, "Private other project", "PRIVATE");
        long third = postForId(f.user, "/api/project/" + pid(elsewhere) + "/trail", "{\"title\":\"Third trail\"}");
        items.attachToTrail(third, f.source, f.user);
        mockMvc.perform(get("/api/project/" + pid(f.project) + "/item/" + f.source + "/extract").header("Authorization", bearer(f.user)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.trailCount").value(3));
    }

    @Test
    void preservesPrivateImageReferencesOutsideTheExtractedTextAfterDeletingNewNote() throws Exception {
        Fixture f = fixture(); UUID imageId = UUID.randomUUID();
        var upload = new com.tramo.backend.upload.entity.UploadRecord();
        upload.setUserId(f.user.getId()); upload.setProjectId(f.project.getId()); upload.setObjectKey("images/" + imageId); upload.setBytes(42L); upload.setCreatedDate(new Date());
        upload = uploads.saveAndFlush(upload);
        images.createObject(imageId, "image/png", 42, "a".repeat(64)); images.createImage(imageId, imageId, f.project.getId(), upload.getId()); images.setState(imageId, "READY");
        JsonNode original = mapper.readTree(ORIGINAL);
        JsonNode image = mapper.readTree("{\"type\":\"image\",\"version\":2,\"imageId\":\"" + imageId + "\"}");
        ((tools.jackson.databind.node.ArrayNode) original.path("root").path("children")).add(image);
        String source = mapper.writeValueAsString(original); items.updateContent(f.source, source, f.user);
        var request = request(f, false, UUID.randomUUID()); JsonNode replacement = mapper.readTree(request.sourceContent());
        ((tools.jackson.databind.node.ArrayNode) replacement.path("root").path("children")).add(image);
        var withImage = new ExtractSelectionRequest(request.operationId(), request.title(), source, 0, mapper.writeValueAsString(replacement), EXTRACTED, null, null);
        JsonNode result = send(f, withImage); long newId = result.path("item").path("id").asLong(); items.delete(newId, f.user);
        assertThat(items.getContent(f.source, f.user).getContent()).contains(imageId.toString(), "tramo-idea:" + newId);
        assertThat(images.visibleImageIds(f.project.getId(), null)).contains(imageId);
        assertThat(images.findAll(List.of(imageId)).get(0).state()).isEqualTo("READY");
    }

}
