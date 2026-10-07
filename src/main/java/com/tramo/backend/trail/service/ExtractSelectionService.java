package com.tramo.backend.trail.service;

import com.tramo.backend.exception.*;
import com.tramo.backend.project.service.AccessGuard;
import com.tramo.backend.trail.dto.*;
import com.tramo.backend.trail.entity.*;
import com.tramo.backend.trail.repository.*;
import com.tramo.backend.user.entity.User;
import jakarta.transaction.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
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
    private static final Set<String> SELECTION_TYPES = Set.of("root", "paragraph", "heading", "quote", "list", "listitem", "text", "link", "autolink", "linebreak", "tab");

    public ExtractSelectionService(ItemService itemService, TrailService trailService, ItemRepository items,
            TrailItemRepository memberships, AccessGuard access, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.itemService = itemService; this.trailService = trailService; this.items = items;
        this.memberships = memberships; this.access = access; this.jdbc = jdbc; this.mapper = mapper;
    }

    public int sharedCount(Long projectId, Long sourceId, User user) {
        access.getOwnedProject(projectId, user);
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
        var project = access.getOwnedProject(projectId, user);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", request.operationId().toString());
        String fingerprint = hash(projectId + ":" + sourceId + ":" + mapper.writeValueAsString(request));
        var completed = jdbc.queryForList("SELECT owner_id, source_id, item_id, request_hash, source_hash FROM note_extraction WHERE operation_id = ?", request.operationId());
        Trail trail = request.trailId() == null ? null : trailService.getOwnedTrailForUpdate(request.trailId(), user);
        if (trail != null && !trail.getProject().getId().equals(projectId)) throw conflict("The trail no longer belongs to this project. Select again.");
        items.lockById(sourceId).orElseThrow(() -> new ResourceNotFoundException("Source note not found"));
        itemService.getContent(sourceId, user);
        Item source = items.findById(sourceId).orElseThrow();
        if (!(source.getProject() != null && source.getProject().getId().equals(projectId))
                && memberships.findByItemId(sourceId).stream().noneMatch(step -> step.getTrail().getProject().getId().equals(projectId)))
            throw conflict("The source note no longer belongs to this project. Select again.");
        if (!completed.isEmpty()) {
            var saved = completed.get(0);
            if (!user.getId().equals(saved.get("owner_id")) || !sourceId.equals(saved.get("source_id")) || !fingerprint.equals(saved.get("request_hash")))
                throw conflict("This extraction identifier was already used for a different selection.");
            Long id = (Long) saved.get("item_id");
            Item created = items.findById(id).orElseThrow(() -> conflict("This extraction already completed, but its note was deleted. Reload the project."));
            if (!hash(content(source)).equals(saved.get("source_hash"))) throw conflict("This extraction already completed and the source changed afterward. Reload the project; do not extract again.");
            return response(created, source, trail);
        }
        if (!Objects.equals(content(source), request.expectedContent()) || source.getContent() == null
                || source.getContent().getExtractionEpoch() != request.extractionEpoch())
            throw conflict("The source note changed. Your selection is untouched; reload and select again.");
        List<TrailItem> steps = trail == null ? List.of() : memberships.findByTrailIdOrderByOrderIndexAsc(trail.getId());
        if (trail != null && (!steps.stream().map(step -> step.getItem().getId()).toList().equals(request.expectedOrder())
                || steps.stream().noneMatch(step -> step.getItem().getId().equals(sourceId))))
            throw conflict("The trail order changed. Refresh the trail and select again.");
        JsonNode extracted = document(request.extractedContent());
        validateSelection(extracted.path("root"), 0);
        JsonNode replacement = document(request.sourceContent());
        String marker = "tramo-extraction:" + request.operationId();
        if (countMarker(replacement.path("root"), marker, request.title().trim(), null, 0) != 1)
            throw conflict("The replacement link is invalid. Select again.");
        ItemRequestDTO create = new ItemRequestDTO(); create.setTitle(request.title().trim()); create.setType(source.getType());
        Long newId = itemService.createLoose(projectId, create, user).getId();
        Item created = items.findById(newId).orElseThrow();
        created.setTitleAlign(source.getTitleAlign());
        itemService.updateContent(newId, request.extractedContent(), 0, user);
        countMarker(replacement.path("root"), marker, request.title().trim(), newId, 0);
        String sourceContent = mapper.writeValueAsString(replacement);
        itemService.updateContent(sourceId, sourceContent, request.extractionEpoch(), user);
        source.getContent().setExtractionEpoch(request.extractionEpoch() + 1);
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
        jdbc.update("INSERT INTO note_extraction(operation_id, owner_id, source_id, item_id, request_hash, source_hash) VALUES (?, ?, ?, ?, ?, ?)",
                request.operationId(), user.getId(), sourceId, newId, fingerprint, hash(sourceContent));
        return response(created, source, trail);
    }
    private ExtractSelectionResponse response(Item created, Item source, Trail trail) {
        var item = new ItemResponseDTO(created.getId(), created.getTitle(), created.getType(), created.getTitleAlign(), created.getCreatedDate(), created.getModifiedDate(), Boolean.TRUE.equals(created.getUnfiled()));
        return new ExtractSelectionResponse(item, content(created), content(source), source.getContent().getExtractionEpoch(), trail == null ? null : trail.getId(),
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
    private int countMarker(JsonNode node, String marker, String title, Long target, int depth) {
        if (depth > 100) throw conflict("The selection is too deeply nested.");
        int count = 0;
        if (marker.equals(node.path("rel").asText(""))) {
            if (!"link".equals(node.path("type").asText("")) || node.path("children").size() != 1
                    || !"text".equals(node.path("children").get(0).path("type").asText(""))
                    || !title.equals(node.path("children").get(0).path("text").asText(""))) throw conflict("The replacement link is invalid.");
            count++;
            if (target != null) { ((ObjectNode) node).put("rel", "tramo-idea:" + target); ((ObjectNode) node).put("url", "#"); }
        }
        for (JsonNode child : node.path("children")) count += countMarker(child, marker, title, target, depth + 1);
        return count;
    }
    private static String content(Item item) { return item.getContent() == null || item.getContent().getContent() == null ? "" : item.getContent().getContent(); }
    private static ExtractionConflictException conflict(String message) { return new ExtractionConflictException(message); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
