package com.tramo.backend.trail.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.trail.dto.AssociationDTO;
import com.tramo.backend.trail.dto.ItemContentResponseDTO;
import com.tramo.backend.trail.dto.ItemRequestDTO;
import com.tramo.backend.trail.dto.ItemResponseDTO;
import com.tramo.backend.trail.dto.TrailItemDTO;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.ItemContent;
import com.tramo.backend.trail.entity.ItemImageReference;
import com.tramo.backend.trail.entity.Association;
import com.tramo.backend.trail.entity.AssociationTargetType;
import com.tramo.backend.trail.entity.AssociationType;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.repository.AssociationRepository;
import com.tramo.backend.trail.repository.ItemImageReferenceRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    private static final long LAST_EDITED_THROTTLE_MS = 60 * 1000L;

    private final ItemRepository itemRepository;
    private final TrailItemRepository trailItemRepository;
    private final AssociationRepository itemLinkRepository;
    private final TrailService trailService;
    private final TrailRepository trailRepository;
    private final ProjectRepository projectRepository;
    private final R2Client r2Client;
    private final PendingImageDeletionRepository pendingImageDeletionRepository;
    private final ItemImageReferenceRepository itemImageReferenceRepository;
    private final ObjectMapper objectMapper;

    public ItemService(ItemRepository itemRepository, TrailItemRepository trailItemRepository,
                        AssociationRepository itemLinkRepository, TrailService trailService,
                        TrailRepository trailRepository, ProjectRepository projectRepository,
                        R2Client r2Client, PendingImageDeletionRepository pendingImageDeletionRepository,
                        ItemImageReferenceRepository itemImageReferenceRepository, ObjectMapper objectMapper) {
        this.itemRepository = itemRepository;
        this.trailItemRepository = trailItemRepository;
        this.itemLinkRepository = itemLinkRepository;
        this.trailService = trailService;
        this.trailRepository = trailRepository;
        this.projectRepository = projectRepository;
        this.r2Client = r2Client;
        this.pendingImageDeletionRepository = pendingImageDeletionRepository;
        this.itemImageReferenceRepository = itemImageReferenceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
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

    
    @Transactional
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

    
    @Transactional
    public void updateStep(Long trailId, Long itemId, String annotation, Long associationId, User requester) {
        trailService.getOwnedTrail(trailId, requester);
        TrailItem step = trailItemRepository.findByTrailIdAndItemId(trailId, itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Step not found"));

        step.setAnnotation(annotation);
        if (associationId == null) {
            step.setAssociation(null);
        } else {
            Association association = itemLinkRepository.findById(associationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Association not found"));
            
            getOwnedItem(association.getSourceItem().getId(), requester);
            step.setAssociation(association);
        }
        trailItemRepository.save(step);
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
        deleteItemCompletely(item);
    }

    private void deleteItemCompletely(Item item) {
        itemLinkRepository.deleteBySourceItemId(item.getId());
        itemLinkRepository.deleteByTargetTypeAndTargetId(AssociationTargetType.ITEM, item.getId());
        trailItemRepository.deleteAll(trailItemRepository.findByItemId(item.getId()));
        itemImageReferenceRepository.deleteByItemId(item.getId());
        itemRepository.delete(item);
    }

    public ItemContentResponseDTO getContent(Long id, User requester) {
        Item item = getOwnedItem(id, requester);
        String content = item.getContent() != null ? item.getContent().getContent() : "";
        return new ItemContentResponseDTO(content);
    }

    @Transactional
    public void updateContent(Long id, String content, User requester) {
        assertImagesAreFromOurDomain(content);
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
        bumpOwningProjectLastEditedDate(item);
        Set<String> newUrls = deleteOrphanedEditorImages(item, id, requester, previousContent, content);
        resyncImageReferences(item, newUrls);
    }

    // Item content is the raw serialized Lexical editor tree - a JSON blob a client controls
    // directly via this endpoint, independent of the editor UI (which only ever inserts images
    // through the upload flow). Without this, a direct API call could embed an image node
    // pointing anywhere (e.g. a tracking pixel), rendered as a raw <img> for anyone who views
    // the item. Walking the actual JSON tree (rather than a regex over the raw string) avoids
    // false positives on plain text that happens to contain the substring "src". Content that
    // isn't valid JSON can't deserialize into a renderable image node in the editor either way,
    // so it's skipped rather than rejected here.
    private void assertImagesAreFromOurDomain(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (Exception e) {
            return;
        }
        assertImageNodesAreFromOurDomain(root);
    }

    private void assertImageNodesAreFromOurDomain(JsonNode node) {
        if (node.isObject() && "image".equals(node.path("type").asText(null))) {
            String src = node.path("src").asText(null);
            if (!r2Client.isFromOurDomain(src)) {
                throw new IllegalArgumentException("Invalid image URL in content");
            }
        }
        for (JsonNode child : node) {
            assertImageNodesAreFromOurDomain(child);
        }
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
                log.info("deleteOrphanedEditorImages queued url={} item={}", url, itemId);
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
                log.info("purgePendingImageDeletions deleting url={}", pending.getUrl());
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
        Trail trail = trailService.getOwnedTrail(trailId, requester);
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
        trailService.getOwnedTrail(trailId, requester);
        Item item = getOwnedItem(itemId, requester);

        trailItemRepository.findByTrailIdAndItemId(trailId, item.getId())
                .ifPresent(trailItemRepository::delete);

        
        
        
        if (trailItemRepository.findByItemId(item.getId()).isEmpty()) {
            if (item.getProject() == null) {
                deleteItemCompletely(item);
            } else {
                item.setUnfiled(true);
                itemRepository.save(item);
            }
        }
    }

    
    @Transactional
    public void tie(Long sourceId, AssociationType type, AssociationTargetType targetType,
                    Long targetId, User requester) {
        Item source = getOwnedItem(sourceId, requester);
        
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

    
    @Transactional
    public void untie(Long sourceId, AssociationTargetType targetType, Long targetId, User requester) {
        getOwnedItem(sourceId, requester);
        itemLinkRepository.findBySourceItemIdAndTargetTypeAndTargetId(sourceId, targetType, targetId)
                .ifPresent(itemLinkRepository::delete);
    }

    
    public List<AssociationDTO> getAssociations(Long id, User requester) {
        Item item = getOwnedItem(id, requester);
        List<Association> associations = itemLinkRepository.findBySourceItemId(item.getId());

        
        
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

    
    private String resolveOwnedTargetTitle(AssociationTargetType targetType, Long targetId, User requester) {
        if (targetType == AssociationTargetType.TRAIL) {
            return trailService.getOwnedTrail(targetId, requester).getTitle();
        }
        return getOwnedItem(targetId, requester).getTitle();
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
