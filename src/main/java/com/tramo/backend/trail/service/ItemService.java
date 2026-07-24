package com.tramo.backend.trail.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.trail.dto.AssociationDTO;
import com.tramo.backend.trail.dto.ItemContentResponseDTO;
import com.tramo.backend.trail.dto.ItemRequestDTO;
import com.tramo.backend.trail.dto.ItemResponseDTO;
import com.tramo.backend.trail.dto.TrailItemDTO;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.ItemContent;
import com.tramo.backend.trail.entity.Association;
import com.tramo.backend.trail.entity.AssociationTargetType;
import com.tramo.backend.trail.entity.AssociationType;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.repository.AssociationRepository;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ItemService {
    private static final Logger log = LoggerFactory.getLogger(ItemService.class);
    private static final long IMAGE_DELETION_GRACE_MS = 24 * 60 * 60 * 1000L;
    private static final long IMAGE_DELETION_PURGE_INTERVAL_MS = 60 * 60 * 1000L;

    private final ItemRepository itemRepository;
    private final TrailItemRepository trailItemRepository;
    private final AssociationRepository itemLinkRepository;
    private final TrailService trailService;
    private final TrailRepository trailRepository;
    private final ProjectRepository projectRepository;
    private final R2Client r2Client;
    private final PendingImageDeletionRepository pendingImageDeletionRepository;

    public ItemService(ItemRepository itemRepository, TrailItemRepository trailItemRepository,
                        AssociationRepository itemLinkRepository, TrailService trailService,
                        TrailRepository trailRepository, ProjectRepository projectRepository,
                        R2Client r2Client, PendingImageDeletionRepository pendingImageDeletionRepository) {
        this.itemRepository = itemRepository;
        this.trailItemRepository = trailItemRepository;
        this.itemLinkRepository = itemLinkRepository;
        this.trailService = trailService;
        this.trailRepository = trailRepository;
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

    // Create a "loose" item that belongs to the project but no trail.
    public ItemResponseDTO createLoose(Long projectId, ItemRequestDTO request, User requester) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("Title is required");
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

    public List<TrailItemDTO> getAllForTrail(Long trailId, User requester) {
        trailService.getOwnedTrail(trailId, requester);
        return trailItemRepository.findByTrailIdOrderByOrderIndexAsc(trailId).stream()
                .map(this::toStepResponse)
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
                item.getModifiedDate(),
                step.getAnnotation(),
                step.getAssociation() != null ? String.valueOf(step.getAssociation().getId()) : null
        );
    }

    // "blaze": set a step's annotation and the association used to reach it.
    @Transactional
    public void updateStep(Long trailId, Long itemId, String annotation, Long associationId, User requester) {
        trailService.getOwnedTrail(trailId, requester);
        TrailItem step = trailItemRepository.findByTrailIdOrderByOrderIndexAsc(trailId).stream()
                .filter(ti -> ti.getItem().getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Step not found"));

        step.setAnnotation(annotation);
        if (associationId == null) {
            step.setAssociation(null);
        } else {
            Association association = itemLinkRepository.findById(associationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Association not found"));
            // The association must originate from an item the requester owns.
            getOwnedItem(association.getSourceItem().getId(), requester);
            step.setAssociation(association);
        }
        trailItemRepository.save(step);
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
        itemLinkRepository.deleteBySourceItemId(item.getId());
        itemLinkRepository.deleteByTargetTypeAndTargetId(AssociationTargetType.ITEM, item.getId());
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

        // Loose-capable items (with a project) stay; if this was their last
        // trail they surface in Unfiled. Legacy items (no project) with no
        // remaining trail are deleted.
        if (trailItemRepository.findByItemId(item.getId()).isEmpty()) {
            if (item.getProject() == null) {
                deleteItemCompletely(item);
            } else {
                item.setUnfiled(true);
                itemRepository.save(item);
            }
        }
    }

    // Create a typed association from an item to another item or a whole trail ("tie").
    public void tie(Long sourceId, AssociationType type, AssociationTargetType targetType,
                    Long targetId, User requester) {
        Item source = getOwnedItem(sourceId, requester);
        // Validate the target exists and the requester owns it.
        String targetTitle = resolveOwnedTargetTitle(targetType, targetId, requester);
        if (targetType == AssociationTargetType.ITEM && sourceId.equals(targetId)) {
            throw new IllegalArgumentException("An item cannot be tied to itself");
        }
        if (targetTitle == null) {
            throw new ResourceNotFoundException("Association target not found");
        }

        if (itemLinkRepository.findBySourceItemIdAndTargetTypeAndTargetId(source.getId(), targetType, targetId).isPresent()) {
            return;
        }

        Association association = new Association();
        association.setSourceItem(source);
        association.setType(type != null ? type : AssociationType.RELATED);
        association.setTargetType(targetType);
        association.setTargetId(targetId);
        association.setCreatedDate(new Date());
        itemLinkRepository.save(association);
    }

    // Remove an association ("untie").
    public void untie(Long sourceId, AssociationTargetType targetType, Long targetId, User requester) {
        getOwnedItem(sourceId, requester);
        itemLinkRepository.findBySourceItemIdAndTargetTypeAndTargetId(sourceId, targetType, targetId)
                .ifPresent(itemLinkRepository::delete);
    }

    // Outgoing associations of an item, with the resolved target title.
    public List<AssociationDTO> getAssociations(Long id, User requester) {
        Item item = getOwnedItem(id, requester);
        List<Association> associations = itemLinkRepository.findBySourceItemId(item.getId());

        // Batch the title lookups by target type so the count is constant (2 queries)
        // instead of one findById per association (N+1).
        Map<Long, String> itemTitles = titlesByIdForType(associations, AssociationTargetType.ITEM,
                itemRepository::findIdTitleByIdIn);
        Map<Long, String> trailTitles = titlesByIdForType(associations, AssociationTargetType.TRAIL,
                trailRepository::findIdTitleByIdIn);

        return associations.stream()
                .map(a -> new AssociationDTO(
                        String.valueOf(a.getId()),
                        a.getType().name(),
                        a.getTargetType().name(),
                        String.valueOf(a.getTargetId()),
                        (a.getTargetType() == AssociationTargetType.TRAIL ? trailTitles : itemTitles)
                                .get(a.getTargetId())
                ))
                .toList();
    }

    private Map<Long, String> titlesByIdForType(List<Association> associations, AssociationTargetType type,
                                                java.util.function.Function<Set<Long>, List<Object[]>> lookup) {
        Set<Long> ids = associations.stream()
                .filter(a -> a.getTargetType() == type)
                .map(Association::getTargetId)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return lookup.apply(ids).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (String) row[1]));
    }

    // Validates ownership of the target and returns its title, or null if it doesn't exist.
    private String resolveOwnedTargetTitle(AssociationTargetType targetType, Long targetId, User requester) {
        if (targetType == AssociationTargetType.TRAIL) {
            return trailService.getOwnedTrail(targetId, requester).getTitle();
        }
        return getOwnedItem(targetId, requester).getTitle();
    }

    private Item getOwnedItem(Long id, User requester) {
        Item item = itemRepository.findById(id)
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
