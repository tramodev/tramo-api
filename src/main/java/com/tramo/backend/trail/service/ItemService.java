// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail.service;

import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import com.tramo.backend.trail.dto.ItemTextStatsDTO;
import com.tramo.backend.trail.dto.ProjectTextStatsDTO;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.trail.dto.ItemContentResponseDTO;
import com.tramo.backend.trail.dto.ItemRequestDTO;
import com.tramo.backend.trail.dto.ItemResponseDTO;
import com.tramo.backend.trail.dto.MapItemPreviewDTO;
import com.tramo.backend.trail.dto.TrailItemContentDTO;
import com.tramo.backend.trail.dto.TrailItemDTO;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.ItemContent;
import com.tramo.backend.trail.entity.ItemImageReference;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.repository.ItemImageReferenceRepository;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.upload.ImageDeletionQueue;
import com.tramo.backend.upload.R2Client;
import com.tramo.backend.upload.service.EditorImageService;
import com.tramo.backend.upload.entity.PendingImageDeletion;
import com.tramo.backend.upload.repository.PendingImageDeletionRepository;
import com.tramo.backend.user.entity.User;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ItemService {
    private static final Logger log = LoggerFactory.getLogger(ItemService.class);
    private static final long IMAGE_DELETION_GRACE_MS = 24 * 60 * 60 * 1000L;
    private static final long IMAGE_DELETION_PURGE_INTERVAL_MS = 60 * 60 * 1000L;
    private static final long LAST_EDITED_THROTTLE_MS = 60 * 1000L;
    private static final int MIN_SEARCH_LENGTH = 3;

    private final ItemRepository itemRepository;
    private final TrailItemRepository trailItemRepository;
    private final TrailService trailService;
    private final TrailRepository trailRepository;
    private final ProjectRepository projectRepository;
    private final R2Client r2Client;
    private final PendingImageDeletionRepository pendingImageDeletionRepository;
    private final ItemImageReferenceRepository itemImageReferenceRepository;
    private final ImageDeletionQueue imageDeletionQueue;
    private final ObjectMapper objectMapper;
    private final EditorImageService editorImages;

    public ItemService(ItemRepository itemRepository, TrailItemRepository trailItemRepository,
                        TrailService trailService,
                        TrailRepository trailRepository, ProjectRepository projectRepository,
                        R2Client r2Client, PendingImageDeletionRepository pendingImageDeletionRepository,
                        ItemImageReferenceRepository itemImageReferenceRepository,
                        ImageDeletionQueue imageDeletionQueue, ObjectMapper objectMapper, EditorImageService editorImages) {
        this.itemRepository = itemRepository;
        this.trailItemRepository = trailItemRepository;
        this.trailService = trailService;
        this.trailRepository = trailRepository;
        this.projectRepository = projectRepository;
        this.r2Client = r2Client;
        this.pendingImageDeletionRepository = pendingImageDeletionRepository;
        this.itemImageReferenceRepository = itemImageReferenceRepository;
        this.imageDeletionQueue = imageDeletionQueue;
        this.objectMapper = objectMapper;
        this.editorImages = editorImages;
    }

    @Transactional
    public ItemResponseDTO create(Long trailId, ItemRequestDTO request, User requester) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new RequestValidationException(RequestErrorCode.TITLE_REQUIRED);
        }
        Trail trail = trailService.getOwnedTrailForUpdate(trailId, requester);

        ItemContent content = new ItemContent();
        content.setContent("");
        content.setUpdatedDate(new Date());

        Item item = new Item();
        item.setTitle(request.getTitle());
        item.setType(request.getType());
        item.setTitleAlign("center");
        item.setContent(content);
        item.setProject(trail.getProject());
        item.setCreatedDate(new Date());
        item.setModifiedDate(new Date());
        item = itemRepository.save(item);

        TrailItem trailItem = new TrailItem();
        trailItem.setTrail(trail);
        trailItem.setItem(item);
        trailItem.setOrderIndex(trailItemRepository.countByTrailId(trailId));
        trailItemRepository.save(trailItem);

        return toResponse(item);
    }

    
    @Transactional
    public ItemResponseDTO createLoose(Long projectId, ItemRequestDTO request, User requester) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new RequestValidationException(RequestErrorCode.TITLE_REQUIRED);
        }
        Project project = getOwnedProject(projectId, requester);

        ItemContent content = new ItemContent();
        content.setContent("");
        content.setUpdatedDate(new Date());

        Item item = new Item();
        item.setTitle(request.getTitle());
        item.setType(request.getType());
        item.setTitleAlign("center");
        item.setContent(content);
        item.setProject(project);
        item.setUnfiled(true);
        item.setCreatedDate(new Date());
        item.setModifiedDate(new Date());
        return toResponse(itemRepository.save(item));
    }

    public List<ItemResponseDTO> getItemsForProject(Long projectId, User requester) {
        getOwnedProject(projectId, requester);
        return itemRepository.findByProjectId(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    public Map<String, MapItemPreviewDTO> getMapPreviews(Long projectId, User requester) {
        getOwnedProject(projectId, requester);
        List<Item> items = itemRepository.findForExport(projectId);
        Set<Long> itemIds = items.stream().map(Item::getId).collect(Collectors.toSet());
        Map<String, MapItemPreviewDTO> previews = new LinkedHashMap<>();
        for (Item item : items) {
            String content = item.getContent() == null ? null : item.getContent().getContent();
            previews.put(String.valueOf(item.getId()), mapPreview(content, itemIds));
        }
        return previews;
    }

    private MapItemPreviewDTO mapPreview(String content, Set<Long> itemIds) {
        if (content == null || content.isBlank()) return new MapItemPreviewDTO("", List.of());
        try {
            JsonNode root = objectMapper.readTree(content).path("root");
            StringBuilder preview = new StringBuilder();
            JsonNode blocks = root.path("children");
            if (blocks.isArray()) {
                for (JsonNode block : blocks) {
                    StringBuilder text = new StringBuilder();
                    appendPlainText(block, text);
                    String trimmed = text.toString().trim();
                    if (trimmed.isEmpty()) continue;
                    if (!preview.isEmpty()) preview.append("\n\n");
                    preview.append(trimmed);
                    if (preview.length() > 256) {
                        preview.setLength(256);
                        preview = new StringBuilder(preview.toString().stripTrailing()).append("...");
                        break;
                    }
                }
            }
            Set<String> linkedIds = new LinkedHashSet<>();
            collectMapLinks(root, itemIds, linkedIds);
            return new MapItemPreviewDTO(preview.toString(), List.copyOf(linkedIds));
        } catch (Exception ignored) {
            return new MapItemPreviewDTO("", List.of());
        }
    }

    private void collectMapLinks(JsonNode node, Set<Long> itemIds, Set<String> linkedIds) {
        if ("link".equals(node.path("type").asText(""))) {
            String rel = node.path("rel").asText("");
            if (rel.startsWith("tramo-idea:") || rel.startsWith("mypath-idea:")) {
                try {
                    Long id = Long.parseLong(rel.substring(rel.indexOf(':') + 1));
                    if (itemIds.contains(id)) linkedIds.add(String.valueOf(id));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (JsonNode child : node.path("children")) collectMapLinks(child, itemIds, linkedIds);
    }

    public List<Long> searchItemIds(Long projectId, String q, User requester) {
        getOwnedProject(projectId, requester);
        String needle = q == null ? "" : q.trim().toLowerCase();
        if (needle.length() < MIN_SEARCH_LENGTH) {
            return List.of();
        }
        return itemRepository.findByProjectId(projectId).stream()
                .filter(item -> plainTextForSearch(item.getContent()).contains(needle))
                .map(Item::getId)
                .toList();
    }

    public ProjectTextStatsDTO getProjectTextStats(Long projectId, User requester) {
        getOwnedProject(projectId, requester);
        var items = itemRepository.findTextStatsByProjectId(projectId);
        return new ProjectTextStatsDTO(
                items.stream().mapToLong(ItemTextStatsDTO::words).sum(),
                items.stream().mapToLong(ItemTextStatsDTO::characters).sum(), items);
    }

    private String plainTextForSearch(ItemContent content) {
        if (content == null || content.getContent() == null || content.getContent().isBlank()) {
            return "";
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(content.getContent());
        } catch (Exception e) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        appendPlainText(root.has("root") ? root.get("root") : root, text);
        return text.toString().toLowerCase();
    }

    private void appendPlainText(JsonNode node, StringBuilder out) {
        String text = node.path("text").asText(null);
        if (text != null) {
            out.append(text);
        } else {
            String equation = node.path("equation").asText(null);
            if (equation != null) {
                out.append(equation);
            }
        }
        JsonNode children = node.path("children");
        if (children.isArray()) {
            for (JsonNode child : children) {
                appendPlainText(child, out);
            }
        }
    }

    public List<TrailItemDTO> getAllForTrail(Long trailId, User requester) {
        trailService.getOwnedTrail(trailId, requester);
        return trailItemRepository.findByTrailIdOrderByOrderIndexAsc(trailId).stream()
                .map(this::toStepResponse)
                .toList();
    }

    @Transactional
    public void reorderTrailItems(Long trailId, List<Long> itemIds, User requester) {
        trailService.getOwnedTrailForUpdate(trailId, requester);
        List<TrailItem> steps = trailItemRepository.findByTrailIdOrderByOrderIndexAsc(trailId);
        Map<Long, TrailItem> byItemId = steps.stream()
                .collect(Collectors.toMap(step -> step.getItem().getId(), step -> step));
        if (itemIds.size() != byItemId.size() || !byItemId.keySet().containsAll(itemIds)) {
            throw new RequestValidationException(RequestErrorCode.TRAIL_ORDER_INVALID);
        }
        for (int index = 0; index < itemIds.size(); index++) {
            byItemId.get(itemIds.get(index)).setOrderIndex(index);
        }
        trailItemRepository.saveAll(steps);
    }

    public List<TrailItemContentDTO> getContentsForTrail(Long trailId, User requester) {
        trailService.getOwnedTrail(trailId, requester);
        return trailItemRepository.findByTrailIdOrderByOrderIndexAsc(trailId).stream()
                .map(step -> {
                    Item item = step.getItem();
                    ItemContent content = item.getContent();
                    return new TrailItemContentDTO(item.getId(), content != null ? content.getContent() : "", content != null ? content.getExtractionEpoch() : 0);
                })
                .toList();
    }

    private TrailItemDTO toStepResponse(TrailItem step) {
        Item item = step.getItem();
        return new TrailItemDTO(
                item.getId(),
                item.getTitle(),
                item.getType(),
                item.getTitleAlign(),
                item.getCreatedDate(),
                item.getModifiedDate()
        );
    }

    
    @Transactional
    public ItemResponseDTO update(Long id, ItemRequestDTO request, User requester) {
        Item item = getOwnedItem(id, requester);
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            item.setTitle(request.getTitle());
        }
        if (request.getType() != null) {
            item.setType(request.getType());
        }
        if (request.getTitleAlign() != null) {
            item.setTitleAlign(request.getTitleAlign());
        }
        item.setModifiedDate(new Date());
        return toResponse(itemRepository.save(item));
    }

    @Transactional
    public void delete(Long id, User requester) {
        Item item = getOwnedItem(id, requester);
        deleteItemCompletely(item, requester.getId());
    }

    private void deleteItemCompletely(Item item, Long ownerId) {
        trailItemRepository.deleteAll(trailItemRepository.findByItemId(item.getId()));
        imageDeletionQueue.queueItemImages(item.getId(), ownerId);
        itemImageReferenceRepository.deleteByItemId(item.getId());
        itemRepository.delete(item);
    }

    @Transactional
    public ItemContentResponseDTO getContent(Long id, User requester) {
        Item item = getOwnedItem(id, requester);
        String content = item.getContent() != null ? item.getContent().getContent() : "";
        return new ItemContentResponseDTO(content, item.getContent() == null ? 0 : item.getContent().getExtractionEpoch());
    }

    @Transactional
    public void updateContent(Long id, String content, User requester) {
        updateContent(id, content, null, null, requester);
    }

    @Transactional
    public void updateContent(Long id, String content, Integer extractionEpoch, User requester) {
        updateContent(id, content, extractionEpoch, null, requester);
    }

    @Transactional
    public void updateContent(Long id, String content, Integer extractionEpoch, String expectedContent, User requester) {
        itemRepository.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        Item item = getOwnedItem(id, requester);
        ItemContent itemContent = item.getContent();
        int epoch = itemContent == null ? 0 : itemContent.getExtractionEpoch();
        if ((extractionEpoch == null && epoch != 0) || (extractionEpoch != null && extractionEpoch != epoch))
            throw new com.tramo.backend.exception.ExtractionConflictException("This note was extracted in another session. Your changes were kept locally; reload the note before saving.");
        String previousContent = itemContent != null ? itemContent.getContent() : null;
        if (expectedContent != null && !Objects.equals(expectedContent, previousContent == null ? "" : previousContent))
            throw new com.tramo.backend.exception.ExtractionConflictException("This note changed in another session. Your changes were kept locally; reload before saving.");
        if (itemContent == null) {
            itemContent = new ItemContent();
            item.setContent(itemContent);
        }
        itemContent.setContent(content);
        itemContent.setUpdatedDate(new Date());
        itemRepository.save(item);
        bumpOwningProjectLastEditedDate(item);
        editorImages.syncItem(item, content);
        resyncImageReferences(item, deleteOrphanedEditorImages(item, id, requester, previousContent, content));
    }

    private Set<String> deleteOrphanedEditorImages(Item item, Long itemId, User requester, String previousContent, String newContent) {
        Set<String> oldUrls = r2Client.extractReferencedUrls(previousContent);
        Set<String> newUrls = r2Client.extractReferencedUrls(newContent);
        for (String url : oldUrls) {
            if (newUrls.contains(url)) {
                continue;
            }
            if (!itemImageReferenceRepository.existsOtherItemReferencingUrl(requester.getId(), url, itemId)
                    && !pendingImageDeletionRepository.existsByUrl(url)) {
                log.info("event=orphan_image_deletion_queued");
                PendingImageDeletion pending = new PendingImageDeletion();
                pending.setUrl(url);
                pending.setOwnerId(requester.getId());
                pending.setRequestedAt(new Date());
                pendingImageDeletionRepository.save(pending);
            }
        }
        return newUrls;
    }

    
    
    private void resyncImageReferences(Item item, Set<String> newUrls) {
        Set<String> existing = Set.copyOf(itemImageReferenceRepository.findUrlsByItemId(item.getId()));
        if (existing.equals(newUrls)) return;

        Set<String> stale = existing.stream().filter(url -> !newUrls.contains(url)).collect(Collectors.toSet());
        if (!stale.isEmpty()) {
            itemImageReferenceRepository.deleteByItemIdAndUrlIn(item.getId(), stale);
        }
        for (String url : newUrls) {
            if (existing.contains(url)) continue;
            ItemImageReference reference = new ItemImageReference();
            reference.setItem(item);
            reference.setUrl(url);
            itemImageReferenceRepository.save(reference);
        }
    }

    @Scheduled(fixedRate = IMAGE_DELETION_PURGE_INTERVAL_MS)
    public void purgePendingImageDeletions() {
        Date cutoff = new Date(System.currentTimeMillis() - IMAGE_DELETION_GRACE_MS);
        for (PendingImageDeletion pending : pendingImageDeletionRepository.findByRequestedAtBefore(cutoff)) {
            if (!itemImageReferenceRepository.existsOtherItemReferencingUrl(pending.getOwnerId(), pending.getUrl(), -1L)) {
                log.info("event=pending_image_deletion_started");
                r2Client.deleteByPublicUrl(pending.getUrl());
            }
            pendingImageDeletionRepository.delete(pending);
        }
    }

    private void bumpOwningProjectLastEditedDate(Item item) {
        Long projectId = item.getProject() != null
                ? item.getProject().getId()
                : trailItemRepository.findByItemId(item.getId()).stream().findFirst()
                        .map(trailItem -> trailItem.getTrail().getProject().getId()).orElse(null);
        if (projectId == null) return;
        Date now = new Date();
        projectRepository.touchLastEditedDate(projectId, now, new Date(now.getTime() - LAST_EDITED_THROTTLE_MS));
    }

    @Transactional
    public void attachToTrail(Long trailId, Long itemId, User requester) {
        Trail trail = trailService.getOwnedTrailForUpdate(trailId, requester);
        Item item = getOwnedItem(itemId, requester);
        if (trailItemRepository.existsByTrailIdAndItemId(trail.getId(), item.getId())) {
            return;
        }
        TrailItem trailItem = new TrailItem();
        trailItem.setTrail(trail);
        trailItem.setItem(item);
        trailItem.setOrderIndex(trailItemRepository.countByTrailId(trailId));
        trailItemRepository.save(trailItem);
    }

    @Transactional
    public void detachFromTrail(Long trailId, Long itemId, User requester) {
        trailService.getOwnedTrailForUpdate(trailId, requester);
        Item item = getOwnedItem(itemId, requester);

        trailItemRepository.findByTrailIdAndItemId(trailId, item.getId())
                .ifPresent(trailItemRepository::delete);

        
        
        
        if (trailItemRepository.findByItemId(item.getId()).isEmpty()) {
            if (item.getProject() == null) {
                deleteItemCompletely(item, requester.getId());
            } else {
                item.setUnfiled(true);
                itemRepository.save(item);
            }
        }
    }

    
    private Item getOwnedItem(Long id, User requester) {
        Item item = itemRepository.findByIdWithProject(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        boolean owns = item.getProject() != null
                ? item.getProject().getOwner().getId().equals(requester.getId())
                : trailItemRepository.findByItemId(id).stream()
                        .anyMatch(pi -> pi.getTrail().getProject().getOwner().getId().equals(requester.getId()));
        if (!owns) {
            throw new AccessDeniedException("Not allowed to access this item");
        }
        return item;
    }

    private Project getOwnedProject(Long projectId, User requester) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        if (!project.getOwner().getId().equals(requester.getId())) {
            throw new AccessDeniedException("Not allowed to access this project");
        }
        return project;
    }

    private ItemResponseDTO toResponse(Item item) {
        return new ItemResponseDTO(
                item.getId(),
                item.getTitle(),
                item.getType(),
                item.getTitleAlign(),
                item.getCreatedDate(),
                item.getModifiedDate(),
                Boolean.TRUE.equals(item.getUnfiled())
        );
    }
}
