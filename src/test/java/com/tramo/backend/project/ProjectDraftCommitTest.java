package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.dto.DraftCommitRequest;
import com.tramo.backend.project.service.ProjectStartService;
import com.tramo.backend.trail.service.ItemService;
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

class ProjectDraftCommitTest extends AbstractIntegrationTest {
    @Autowired private ProjectStartService starter;
    @Autowired private com.tramo.backend.project.repository.ProjectDraftCommitRepository receipts;
    @Autowired private com.tramo.backend.trail.repository.TrailRepository trails;
    @Autowired private com.tramo.backend.trail.repository.ItemRepository items;
    @Autowired private com.tramo.backend.project.service.ProjectService projectService;
    @MockitoSpyBean private ItemService itemService;

    private DraftCommitRequest request(UUID id, String content) {
        return new DraftCommitRequest(id, new DraftCommitRequest.NamedEntity("My project"),
                new DraftCommitRequest.NamedEntity("My trail"), new DraftCommitRequest.DraftItem("My note", "left", content));
    }

    @Test
    void concurrentRetriesReturnOriginalIdsAndDoNotOverwriteContent() throws Exception {
        var owner = createUser("draftowner");
        var request = request(UUID.randomUUID(), "first");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> starter.commitDraft(request, owner));
            var second = executor.submit(() -> starter.commitDraft(request, owner));
            var result = first.get();
            assertThat(second.get()).isEqualTo(result);
            assertThat(itemService.getAllForTrail(result.trailId(), owner).get(0).titleAlign()).isEqualTo("left");
            assertThat(projectRepository.count()).isEqualTo(1);
            assertThat(itemService.getContent(result.itemId(), owner).getContent()).isEqualTo("first");
            itemService.updateContent(result.itemId(), "latest", owner);
            assertThat(starter.commitDraft(request(request.clientRequestId(), "stale"), owner)).isEqualTo(result);
            assertThat(itemService.getContent(result.itemId(), owner).getContent()).isEqualTo("latest");
            var other = createUser("draftother");
            assertThat(starter.commitDraft(request, other).projectId()).isNotEqualTo(result.projectId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failureRollsBackEveryEntityAndAllowsRetry() {
        var owner = createUser("draftrollback");
        var request = request(UUID.randomUUID(), "first");
        doThrow(new IllegalStateException("injected")).when(itemService).updateContent(anyLong(), anyString(), any());
        assertThatThrownBy(() -> starter.commitDraft(request, owner)).isInstanceOf(IllegalStateException.class);
        assertThat(projectRepository.count()).isZero();
        assertThat(trails.count()).isZero();
        assertThat(items.count()).isZero();
        assertThat(receipts.count()).isZero();
        doCallRealMethod().when(itemService).updateContent(anyLong(), anyString(), any());
        starter.commitDraft(request, owner);
        assertThat(projectRepository.count()).isEqualTo(1);
    }

    @Test
    void replayAfterDeletionNeverCreatesAnotherProject() {
        var owner = createUser("draftdeleted");
        var request = request(UUID.randomUUID(), "content");
        var result = starter.commitDraft(request, owner);
        projectService.delete(projectIdCodec.decode(result.projectId()), owner);
        assertThatThrownBy(() -> starter.commitDraft(request, owner))
                .isInstanceOf(com.tramo.backend.exception.ResourceNotFoundException.class);
        assertThat(receipts.count()).isEqualTo(1);
        assertThat(projectRepository.count()).isZero();
    }

    @Test
    void blankTitlesUseDefaultsAndOversizedContentRollsBack() {
        var owner = createUser("draftdefaults");
        var empty = new DraftCommitRequest.NamedEntity(" ");
        var request = new DraftCommitRequest(UUID.randomUUID(), empty, empty,
                new DraftCommitRequest.DraftItem("", "right", "{\"root\":{\"children\":[{\"type\":\"text\",\"text\":\"Written\"}]}}"));
        var result = starter.commitDraft(request, owner);
        assertThat(result.projectTitle()).isEqualTo("Untitled project");
        assertThat(result.trailTitle()).isEqualTo("My first trail");
        assertThat(result.itemTitle()).isEqualTo("Untitled note");
        assertThat(itemService.getAllForTrail(result.trailId(), owner).get(0).titleAlign()).isEqualTo("right");
        assertThatThrownBy(() -> starter.commitDraft(request(UUID.randomUUID(), "x".repeat(1024 * 1024 + 1)), owner))
                .isInstanceOf(com.tramo.backend.exception.PayloadTooLargeException.class);
        assertThat(projectRepository.count()).isEqualTo(1);
        assertThat(receipts.count()).isEqualTo(1);
    }

    @Test
    void emptyBodiesAndInvisibleTitlesDoNotCreateProjects() {
        var owner = createUser("draftempty");
        var empty = new DraftCommitRequest.NamedEntity("\u200b");
        for (String content : java.util.List.of("", "{\"root\":{\"children\":[]}}", "{\"root\":{\"children\":[{\"type\":\"text\",\"text\":\"\u200b\"}]}}")) {
            var request = new DraftCommitRequest(UUID.randomUUID(), empty, empty, new DraftCommitRequest.DraftItem("", "left", content));
            assertThatThrownBy(() -> starter.commitDraft(request, owner)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(projectRepository.count()).isZero();
        assertThat(receipts.count()).isZero();
    }

    @Test
    void receiptsExpireAfterThirtyDays() {
        var owner = createUser("draftretention");
        starter.commitDraft(request(UUID.randomUUID(), "Written"), owner);
        var receipt = receipts.findAll().get(0);
        receipt.setCreatedAt(java.util.Date.from(java.time.Instant.now().minus(31, java.time.temporal.ChronoUnit.DAYS)));
        receipts.saveAndFlush(receipt);
        starter.commitDraft(request(UUID.randomUUID(), "New"), owner);
        starter.purgeDraftCommits();
        assertThat(receipts.count()).isEqualTo(1);
        assertThat(projectRepository.count()).isEqualTo(2);
    }

    @Test
    void endpointRequiresAuthenticationAndValidatesBeforeCreating() throws Exception {
        mockMvc.perform(post("/api/project/draft-commit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        var owner = createUser("draftvalidation");
        mockMvc.perform(post("/api/project/draft-commit").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(projectRepository.count()).isZero();
    }
}
