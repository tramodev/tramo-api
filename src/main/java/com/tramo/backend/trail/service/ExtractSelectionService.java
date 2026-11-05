package com.tramo.backend.trail.service;

import com.tramo.backend.project.entity.ProjectRole;
import com.tramo.backend.project.service.ProjectVersionCapture;
import com.tramo.backend.exception.*;
import com.tramo.backend.project.service.AccessGuard;
import com.tramo.backend.common.ContentLimits;
import com.tramo.backend.trail.dto.*;
import com.tramo.backend.trail.entity.*;
import com.tramo.backend.trail.repository.*;
import com.tramo.backend.user.entity.User;
import jakarta.transaction.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class ExtractSelectionService {
    private final ItemService itemService;
    private final TrailService trailService;
    private final ItemRepository items;
    private final TrailItemRepository memberships;
    private final AccessGuard access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ProjectVersionCapture history;
    private static final Set<String> SELECTION_TYPES = Set.of("root", "paragraph", "heading", "quote", "list", "listitem", "text", "link", "autolink", "linebreak", "tab");

    public ExtractSelectionService(ItemService itemService, TrailService trailService, ItemRepository items,
            TrailItemRepository memberships, AccessGuard access, JdbcTemplate jdbc, ObjectMapper mapper, ProjectVersionCapture history) {
        this.history = history;
        this.itemService = itemService; this.trailService = trailService; this.items = items;
        this.memberships = memberships; this.access = access; this.jdbc = jdbc; this.mapper = mapper;
    }

    public int sharedCount(Long projectId, Long sourceId, User user) {
        access.getProject(projectId, user, ProjectRole.VIEWER);
        itemService.getContent(sourceId, user);
        Item source = items.findByIdWithProject(sourceId).orElseThrow();
        var steps = memberships.findByItemId(sourceId);
        if (!(source.getProject() != null && source.getProject().getId().equals(projectId))
                && steps.stream().noneMatch(step -> step.getTrail().getProject().getId().equals(projectId)))
            throw conflict("The source note no longer belongs to this project.");
        return steps.size();
    }

    @Transactional
    public ExtractSelectionResponse extract(Long projectId, Long sourceId, ExtractSelectionRequest request, User user) {
        ContentLimits.assertEditorNote(request.extractedContent());
        history.lock(projectId, user);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", request.operationId().toString());
        String fingerprint = hash(projectId + ":" + sourceId + ":" + mapper.writeValueAsString(request));
        var completed = jdbc.queryForList("SELECT owner_id, source_id, item_id, request_hash FROM note_extraction WHERE operation_id = ?", request.operationId());
        Trail trail = request.trailId() == null ? null : trailService.getOwnedTrailForUpdate(request.trailId(), user);
        if (trail != null && !trail.getProject().getId().equals(projectId)) throw conflict("The trail no longer belongs to this project. Select again.");
        itemService.getContent(sourceId, user);
        Item source = items.findById(sourceId).orElseThrow(() -> new ResourceNotFoundException("Source note not found"));
        if (!(source.getProject() != null && source.getProject().getId().equals(projectId))
                && memberships.findByItemId(sourceId).stream().noneMatch(step -> step.getTrail().getProject().getId().equals(projectId)))
            throw conflict("The source note no longer belongs to this project. Select again.");
        if (!completed.isEmpty()) {
            var saved = completed.get(0);
            if (!user.getId().equals(saved.get("owner_id")) || !sourceId.equals(saved.get("source_id")) || !fingerprint.equals(saved.get("request_hash")))
                throw conflict("This extraction identifier was already used for a different selection.");
            Long id = (Long) saved.get("item_id");
            Item created = items.findById(id).orElseThrow(() -> conflict("This extraction already completed, but its note was deleted. Reload the project."));
            return response(created, trail);
        }
        List<TrailItem> steps = trail == null ? List.of() : memberships.findByTrailIdOrderByOrderIndexAsc(trail.getId());
        if (trail != null && (!steps.stream().map(step -> step.getItem().getId()).toList().equals(request.expectedOrder())
                || steps.stream().noneMatch(step -> step.getItem().getId().equals(sourceId))))
            throw conflict("The trail order changed. Refresh the trail and select again.");
        validateSelection(document(request.extractedContent()).path("root"), 0);
        ItemRequestDTO create = new ItemRequestDTO(); create.setTitle(request.title().trim()); create.setType(source.getType());
        Long newId = itemService.createLoose(projectId, create, user).getId();
        Item created = items.findById(newId).orElseThrow();
        created.setTitleAlign(source.getTitleAlign());
        itemService.updateContent(newId, request.extractedContent(), 0, user);
        if (trail != null) {
            List<TrailItem> ordered = new ArrayList<>(steps);
            TrailItem next = new TrailItem(); next.setTrail(trail); next.setItem(created);
            int position = request.appendToTrail() ? ordered.size() : steps.stream().map(step -> step.getItem().getId()).toList().indexOf(sourceId) + 1;
            ordered.add(position, next);
            for (int index = 0; index < ordered.size(); index++) ordered.get(index).setOrderIndex(index);
            memberships.saveAll(ordered);
            created.setUnfiled(false);
        }
        items.flush();
        jdbc.update("INSERT INTO note_extraction(operation_id, owner_id, source_id, item_id, request_hash) VALUES (?, ?, ?, ?, ?)",
                request.operationId(), user.getId(), sourceId, newId, fingerprint);
        return response(created, trail);
    }
    private ExtractSelectionResponse response(Item created, Trail trail) {
        var item = new ItemResponseDTO(created.getId(), created.getTitle(), created.getType(), created.getTitleAlign(), created.getCreatedDate(), created.getModifiedDate(), Boolean.TRUE.equals(created.getUnfiled()));
        return new ExtractSelectionResponse(item, content(created), trail == null ? null : trail.getId(),
                trail == null ? List.of() : itemService.getAllForTrail(trail.getId(), trail.getProject().getOwner()));
    }
    private JsonNode document(String content) {
        JsonNode tree;
        try { tree = mapper.readTree(content); }
        catch (RuntimeException invalid) { throw conflict("The selected content is invalid. Select again."); }
        if (!"root".equals(tree.path("root").path("type").asText("")) || !tree.path("root").path("children").isArray())
            throw conflict("The selected content is invalid. Select again.");
        return tree;
    }
    private void validateSelection(JsonNode node, int depth) {
        if (depth > 100 || !SELECTION_TYPES.contains(node.path("type").asText(""))) throw conflict("This selection contains an unsupported block. Select text, paragraphs or lists instead.");
        for (JsonNode child : node.path("children")) validateSelection(child, depth + 1);
    }
    private static String content(Item item) { return item.getContent() == null || item.getContent().getContent() == null ? "" : item.getContent().getContent(); }
    private static ExtractionConflictException conflict(String message) { return new ExtractionConflictException(message); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
