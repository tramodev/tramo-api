package com.tramo.backend.trail.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.trail.dto.ItemContentResponseDTO;
import com.tramo.backend.trail.dto.ItemRequestDTO;
import com.tramo.backend.trail.dto.ItemResponseDTO;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.ItemContent;
import com.tramo.backend.trail.entity.Association;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.repository.AssociationRepository;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.upload.R2Client;
import com.tramo.backend.upload.entity.PendingImageDeletion;
import com.tramo.backend.upload.repository.PendingImageDeletionRepository;
import com.tramo.backend.user.entity.User;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Set;

@Service
public class ItemService {
    private static final Logger log = LoggerFactory.getLogger(ItemService.class);
    private static final long IMAGE_DELETION_GRACE_MS = 24 * 60 * 60 * 1000L;
    private static final long IMAGE_DELETION_PURGE_INTERVAL_MS = 60 * 60 * 1000L;

    private final ItemRepository itemRepository;
    private final TrailItemRepository trailItemRepository;
    private final AssociationRepository itemLinkRepository;
    private final TrailService trailService;
    private final ProjectRepository projectRepository;
    private final R2Client r2Client;
    private final PendingImageDeletionRepository pendingImageDeletionRepository;

    public ItemService(ItemRepository itemRepository, TrailItemRepository trailItemRepository,
                        AssociationRepository itemLinkRepository, TrailService trailService,
                        ProjectRepository projectRepository, R2Client r2Client,
                        PendingImageDeletionRepository pendingImageDeletionRepository) {
        this.itemRepository = itemRepository;
        this.trailItemRepository = trailItemRepository;
        this.itemLinkRepository = itemLinkRepository;
        this.trailService = trailService;
        this.projectRepository = projectRepository;
        this.r2Client = r2Client;
        this.pendingImageDeletionRepository = pendingImageDeletionRepository;
    }

    public ItemResponseDTO create(Long trailId, ItemRequestDTO request, User requester) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        Trail trail = trailService.getOwnedTrail(trailId, requester);

        ItemContent content = new ItemContent();
        content.setContent("");
        content.setUpdatedDate(new Date());

        Item item = new Item();
        item.setTitle(request.getTitle());
        item.setType(request.getType());
        item.setTitleAlign("center");
        item.setContent(content);
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

    public List<ItemResponseDTO> getAllForTrail(Long trailId, User requester) {
        trailService.getOwnedTrail(trailId, requester);
        return trailItemRepository.findByTrailIdOrderByOrderIndexAsc(trailId).stream()
                .map(pi -> toResponse(pi.getItem()))
                .toList();
    }

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
        deleteItemCompletely(item);
    }

    private void deleteItemCompletely(Item item) {
        itemLinkRepository.deleteBySourceItemIdOrTargetItemId(item.getId(), item.getId());
        trailItemRepository.deleteAll(trailItemRepository.findByItemId(item.getId()));
        itemRepository.delete(item);
    }

    public ItemContentResponseDTO getContent(Long id, User requester) {
        Item item = getOwnedItem(id, requester);
        String content = item.getContent() != null ? item.getContent().getContent() : "";
        return new ItemContentResponseDTO(content);
    }

    @Transactional
    public void updateContent(Long id, String content, User requester) {
        Item item = getOwnedItem(id, requester);
        ItemContent itemContent = item.getContent();
        String previousContent = itemContent != null ? itemContent.getContent() : null;
        if (itemContent == null) {
            itemContent = new ItemContent();
            item.setContent(itemContent);
        }
        itemContent.setContent(content);
        itemContent.setUpdatedDate(new Date());
        itemRepository.save(item);
        bumpOwningProjectLastEditedDate(id);
        deleteOrphanedEditorImages(id, requester, previousContent, content);
    }

    private void deleteOrphanedEditorImages(Long itemId, User requester, String previousContent, String newContent) {
        Set<String> oldUrls = r2Client.extractReferencedUrls(previousContent);
        Set<String> newUrls = r2Client.extractReferencedUrls(newContent);
        for (String url : oldUrls) {
            if (newUrls.contains(url)) {
                continue;
            }
            if (!trailItemRepository.existsOtherItemReferencingUrl(requester.getId(), url, itemId)
                    && !pendingImageDeletionRepository.existsByUrl(url)) {
                log.info("deleteOrphanedEditorImages queued url={} item={}", url, itemId);
                PendingImageDeletion pending = new PendingImageDeletion();
                pending.setUrl(url);
                pending.setOwnerId(requester.getId());
                pending.setRequestedAt(new Date());
                pendingImageDeletionRepository.save(pending);
            }
        }
    }

    @Scheduled(fixedRate = IMAGE_DELETION_PURGE_INTERVAL_MS)
    @Transactional
    public void purgePendingImageDeletions() {
        Date cutoff = new Date(System.currentTimeMillis() - IMAGE_DELETION_GRACE_MS);
        for (PendingImageDeletion pending : pendingImageDeletionRepository.findByRequestedAtBefore(cutoff)) {
            if (!trailItemRepository.existsOtherItemReferencingUrl(pending.getOwnerId(), pending.getUrl(), -1L)) {
                log.info("purgePendingImageDeletions deleting url={}", pending.getUrl());
                r2Client.deleteByPublicUrl(pending.getUrl());
            }
            pendingImageDeletionRepository.delete(pending);
        }
    }

    private void bumpOwningProjectLastEditedDate(Long itemId) {
        trailItemRepository.findByItemId(itemId).stream().findFirst().ifPresent(trailItem -> {
            Project project = trailItem.getTrail().getProject();
            project.setLastEditedDate(new Date());
            projectRepository.save(project);
        });
    }

    public void attachToTrail(Long trailId, Long itemId, User requester) {
        Trail trail = trailService.getOwnedTrail(trailId, requester);
        Item item = getOwnedItem(itemId, requester);
        boolean alreadyAttached = trailItemRepository.findByItemId(item.getId()).stream()
                .anyMatch(pi -> pi.getTrail().getId().equals(trail.getId()));
        if (alreadyAttached) {
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
        trailService.getOwnedTrail(trailId, requester);
        Item item = getOwnedItem(itemId, requester);

        trailItemRepository.findByTrailIdOrderByOrderIndexAsc(trailId).stream()
                .filter(pi -> pi.getItem().getId().equals(item.getId()))
                .findFirst()
                .ifPresent(trailItemRepository::delete);

        if (trailItemRepository.findByItemId(item.getId()).isEmpty()) {
            deleteItemCompletely(item);
        }
    }

    public void linkItems(Long sourceId, Long targetId, User requester) {
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("An item cannot be linked to itself");
        }
        Item source = getOwnedItem(sourceId, requester);
        Item target = getOwnedItem(targetId, requester);

        if (itemLinkRepository.findBySourceItemIdAndTargetItemId(source.getId(), target.getId()).isPresent()
                || itemLinkRepository.findBySourceItemIdAndTargetItemId(target.getId(), source.getId()).isPresent()) {
            return;
        }

        Association link = new Association();
        link.setSourceItem(source);
        link.setTargetItem(target);
        link.setCreatedDate(new Date());
        itemLinkRepository.save(link);
    }

    public void unlinkItems(Long sourceId, Long targetId, User requester) {
        getOwnedItem(sourceId, requester);
        getOwnedItem(targetId, requester);
        itemLinkRepository.findBySourceItemIdAndTargetItemId(sourceId, targetId).ifPresent(itemLinkRepository::delete);
        itemLinkRepository.findBySourceItemIdAndTargetItemId(targetId, sourceId).ifPresent(itemLinkRepository::delete);
    }

    public List<ItemResponseDTO> getLinkedItems(Long id, User requester) {
        Item item = getOwnedItem(id, requester);
        return itemLinkRepository.findBySourceItemIdOrTargetItemId(item.getId(), item.getId()).stream()
                .map(link -> link.getSourceItem().getId().equals(item.getId()) ? link.getTargetItem() : link.getSourceItem())
                .map(this::toResponse)
                .toList();
    }

    private Item getOwnedItem(Long id, User requester) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        boolean owns = trailItemRepository.findByItemId(id).stream()
                .anyMatch(pi -> pi.getTrail().getProject().getOwner().getId().equals(requester.getId()));
        if (!owns) {
            throw new AccessDeniedException("Not allowed to access this item");
        }
        return item;
    }

    private ItemResponseDTO toResponse(Item item) {
        return new ItemResponseDTO(
                item.getId(),
                item.getTitle(),
                item.getType(),
                item.getTitleAlign(),
                item.getCreatedDate(),
                item.getModifiedDate()
        );
    }
}
