package com.tramo.backend.project.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.dto.ProjectResponseDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectSnapshot;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectSnapshotRepository;
import com.tramo.backend.project.snapshot.ProjectSnapshotData;
import com.tramo.backend.tag.service.TagService;
import com.tramo.backend.trail.entity.Association;
import com.tramo.backend.trail.entity.AssociationTargetType;
import com.tramo.backend.trail.entity.AssociationType;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.ItemContent;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.repository.AssociationRepository;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.BlockedUserRepository;
import com.tramo.backend.user.service.PrivacyPolicy;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class ProjectForkService {
    private final AccessGuard accessGuard;
    private final ProjectRepository projectRepository;
    private final ProjectSnapshotRepository projectSnapshotRepository;
    private final TrailRepository trailRepository;
    private final TrailItemRepository trailItemRepository;
    private final ItemRepository itemRepository;
    private final AssociationRepository itemLinkRepository;
    private final BlockedUserRepository blockedUserRepository;
    private final PrivacyPolicy privacyPolicy;
    private final TagService tagService;
    private final NotificationService notificationService;
    private final BadgeService badgeService;
    private final ProjectResponseMapper responseMapper;
    private final ObjectMapper objectMapper;

    public ProjectForkService(AccessGuard accessGuard, ProjectRepository projectRepository,
                               ProjectSnapshotRepository projectSnapshotRepository, TrailRepository trailRepository,
                               TrailItemRepository trailItemRepository, ItemRepository itemRepository,
                               AssociationRepository itemLinkRepository, BlockedUserRepository blockedUserRepository,
                               PrivacyPolicy privacyPolicy, TagService tagService,
                               NotificationService notificationService, BadgeService badgeService,
                               ProjectResponseMapper responseMapper, ObjectMapper objectMapper) {
        this.accessGuard = accessGuard;
        this.projectRepository = projectRepository;
        this.projectSnapshotRepository = projectSnapshotRepository;
        this.trailRepository = trailRepository;
        this.trailItemRepository = trailItemRepository;
        this.itemRepository = itemRepository;
        this.itemLinkRepository = itemLinkRepository;
        this.blockedUserRepository = blockedUserRepository;
        this.privacyPolicy = privacyPolicy;
        this.tagService = tagService;
        this.notificationService = notificationService;
        this.badgeService = badgeService;
        this.responseMapper = responseMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProjectResponseDTO fork(Long sourceProjectId, User requester) {
        Project source = projectRepository.findById(sourceProjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        accessGuard.assertViewable(source, requester);
        if (source.getOwner().getId().equals(requester.getId())) {
            throw new AccessDeniedException("Cannot fork your own project");
        }
        if (blockedUserRepository.existsEitherDirection(requester.getId(), source.getOwner().getId())) {
            throw new AccessDeniedException("Cannot fork this project");
        }
        if (!privacyPolicy.canFork(source.getOwner())) {
            throw new AccessDeniedException("Forking is disabled for this project");
        }

        Project fork = new Project();
        fork.setTitle(source.getTitle());
        fork.setDescription(source.getDescription());
        fork.setVisibility(ProjectVisibility.PRIVATE);
        fork.setOwner(requester);
        fork.setForkedFrom(source);
        fork.setCreationDate(new Date());
        fork.setModifiedDate(new Date());
        tagService.applyProjectTags(fork, responseMapper.liveTagNames(source), requester.getId());
        fork = projectRepository.save(fork);

        Optional<ProjectSnapshot> latestPublish = projectSnapshotRepository
                .findLatestPublishByProjectIdIn(List.of(sourceProjectId)).stream().findFirst();
        if (latestPublish.isPresent()) {
            forkFromSnapshot(fork, latestPublish.get());
        } else {
            forkFromLiveTables(fork, sourceProjectId);
        }

        notificationService.recordEvent(source.getOwner(), "FORK", source, requester);
        badgeService.checkAndAwardBadges(source.getOwner());
        return responseMapper.toResponse(fork, responseMapper.liveTagNames(fork));
    }

    private void forkFromLiveTables(Project fork, Long sourceProjectId) {
        Map<Long, Item> itemCopies = new HashMap<>();
        Map<Long, Trail> trailCopies = new HashMap<>();

        List<TrailItem> sourceSteps = new ArrayList<>();
        List<TrailItem> copiedSteps = new ArrayList<>();
        for (Trail sourceTrail : trailRepository.findByProjectId(sourceProjectId)) {
            Trail trailCopy = new Trail();
            trailCopy.setTitle(sourceTrail.getTitle());
            trailCopy.setVisibility(sourceTrail.getVisibility());
            trailCopy.setCreationDate(new Date());
            trailCopy.setModifiedDate(new Date());
            trailCopy.setProject(fork);
            trailCopy.setForkedFrom(sourceTrail);
            trailCopy = trailRepository.save(trailCopy);
            trailCopies.put(sourceTrail.getId(), trailCopy);

            for (TrailItem membership : trailItemRepository.findByTrailIdOrderByOrderIndexAsc(sourceTrail.getId())) {
                Item itemCopy = itemCopies.computeIfAbsent(membership.getItem().getId(),
                        ignored -> copyItem(membership.getItem(), fork, false));

                TrailItem membershipCopy = new TrailItem();
                membershipCopy.setTrail(trailCopy);
                membershipCopy.setItem(itemCopy);
                membershipCopy.setOrderIndex(membership.getOrderIndex());
                membershipCopy.setAnnotation(membership.getAnnotation());
                membershipCopy = trailItemRepository.save(membershipCopy);
                sourceSteps.add(membership);
                copiedSteps.add(membershipCopy);
            }
        }

        Map<Long, Association> assocCopies = new HashMap<>();
        for (Long sourceItemId : itemCopies.keySet()) {
            for (Association assoc : itemLinkRepository.findBySourceItemId(sourceItemId)) {
                Long newTargetId = switch (assoc.getTargetType()) {
                    case ITEM -> {
                        Item t = itemCopies.get(assoc.getTargetId());
                        yield t != null ? t.getId() : null;
                    }
                    case TRAIL -> {
                        Trail t = trailCopies.get(assoc.getTargetId());
                        yield t != null ? t.getId() : null;
                    }
                };
                if (newTargetId == null) continue;

                Association copy = new Association();
                copy.setSourceItem(itemCopies.get(sourceItemId));
                copy.setType(assoc.getType());
                copy.setTargetType(assoc.getTargetType());
                copy.setTargetId(newTargetId);
                copy.setCreatedDate(new Date());
                assocCopies.put(assoc.getId(), itemLinkRepository.save(copy));
            }
        }

        for (int i = 0; i < sourceSteps.size(); i++) {
            Association srcAssoc = sourceSteps.get(i).getAssociation();
            if (srcAssoc == null) continue;
            Association newAssoc = assocCopies.get(srcAssoc.getId());
            if (newAssoc == null) continue;
            TrailItem copy = copiedSteps.get(i);
            copy.setAssociation(newAssoc);
            trailItemRepository.save(copy);
        }
    }

    private Item copyItem(Item source, Project fork, boolean unfiled) {
        Item copy = new Item();
        copy.setProject(fork);
        copy.setUnfiled(unfiled);
        copy.setTitle(source.getTitle());
        copy.setType(source.getType());
        copy.setCreatedDate(new Date());
        copy.setModifiedDate(new Date());
        if (source.getContent() != null) {
            ItemContent contentCopy = new ItemContent();
            contentCopy.setContent(source.getContent().getContent());
            contentCopy.setUpdatedDate(new Date());
            copy.setContent(contentCopy);
        }
        return itemRepository.save(copy);
    }

    private void forkFromSnapshot(Project fork, ProjectSnapshot snapshot) {
        ProjectSnapshotData data = objectMapper.readValue(snapshot.getContent(), ProjectSnapshotData.class);

        Map<Long, Item> itemCopies = new HashMap<>();
        List<Long> stepArrivalAssociationIds = new ArrayList<>();
        List<TrailItem> copiedSteps = new ArrayList<>();

        for (ProjectSnapshotData.TrailData trailData : data.trails()) {
            Trail trailCopy = new Trail();
            trailCopy.setTitle(trailData.title());
            trailCopy.setDescription(trailData.description());
            trailCopy.setVisibility(trailData.visibility());
            trailCopy.setCreationDate(new Date());
            trailCopy.setModifiedDate(new Date());
            trailCopy.setProject(fork);
            trailCopy.setForkedFrom(trailRepository.findById(trailData.id()).orElse(null));
            trailCopy = trailRepository.save(trailCopy);

            int orderIndex = 0;
            for (ProjectSnapshotData.ItemData itemData : trailData.items()) {
                Item itemCopy = itemCopies.computeIfAbsent(itemData.id(),
                        ignored -> copyItemFromData(itemData, fork, false));

                TrailItem membershipCopy = new TrailItem();
                membershipCopy.setTrail(trailCopy);
                membershipCopy.setItem(itemCopy);
                membershipCopy.setOrderIndex(orderIndex++);
                membershipCopy.setAnnotation(itemData.annotation());
                membershipCopy = trailItemRepository.save(membershipCopy);
                stepArrivalAssociationIds.add(itemData.associationId());
                copiedSteps.add(membershipCopy);
            }
        }

        for (ProjectSnapshotData.ItemData itemData : data.looseItems()) {
            itemCopies.computeIfAbsent(itemData.id(), ignored -> copyItemFromData(itemData, fork, true));
        }

        List<ProjectSnapshotData.ItemData> allItemData = Stream.concat(
                data.trails().stream().flatMap(trailData -> trailData.items().stream()),
                data.looseItems().stream()).toList();

        Map<Long, Association> assocCopies = new HashMap<>();
        for (ProjectSnapshotData.ItemData itemData : allItemData) {
            Item sourceCopy = itemCopies.get(itemData.id());
            for (ProjectSnapshotData.AssociationData assocData : itemData.associations()) {
                if (assocCopies.containsKey(assocData.id())) continue;
                Item targetCopy = itemCopies.get(assocData.targetId());
                if (targetCopy == null) continue;

                Association copy = new Association();
                copy.setSourceItem(sourceCopy);
                copy.setType(AssociationType.valueOf(assocData.type()));
                copy.setTargetType(AssociationTargetType.ITEM);
                copy.setTargetId(targetCopy.getId());
                copy.setCreatedDate(new Date());
                assocCopies.put(assocData.id(), itemLinkRepository.save(copy));
            }
        }

        for (int i = 0; i < copiedSteps.size(); i++) {
            Long srcAssocId = stepArrivalAssociationIds.get(i);
            if (srcAssocId == null) continue;
            Association newAssoc = assocCopies.get(srcAssocId);
            if (newAssoc == null) continue;
            TrailItem copy = copiedSteps.get(i);
            copy.setAssociation(newAssoc);
            trailItemRepository.save(copy);
        }
    }

    private Item copyItemFromData(ProjectSnapshotData.ItemData itemData, Project fork, boolean unfiled) {
        Item copy = new Item();
        copy.setProject(fork);
        copy.setUnfiled(unfiled);
        copy.setTitle(itemData.title());
        copy.setType(itemData.type());
        copy.setTitleAlign(itemData.titleAlign());
        copy.setCreatedDate(new Date());
        copy.setModifiedDate(new Date());
        if (itemData.content() != null) {
            ItemContent contentCopy = new ItemContent();
            contentCopy.setContent(itemData.content());
            contentCopy.setUpdatedDate(new Date());
            copy.setContent(contentCopy);
        }
        return itemRepository.save(copy);
    }
}
