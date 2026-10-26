// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.common.ContentLimits;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.upload.service.EditorImageService;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.dto.ProjectResponseDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectSnapshotRepository;
import com.tramo.backend.project.snapshot.ProjectSnapshotData;
import com.tramo.backend.project.snapshot.SnapshotCapture;
import com.tramo.backend.project.snapshot.SnapshotLimits;
import com.tramo.backend.tag.service.TagService;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.ItemContent;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class ProjectForkService {
    private final AccessGuard accessGuard;
    private final ProjectRepository projectRepository;
    private final ProjectSnapshotRepository projectSnapshotRepository;
    private final TrailRepository trailRepository;
    private final TrailItemRepository trailItemRepository;
    private final ItemRepository itemRepository;
    private final BlockedUserRepository blockedUserRepository;
    private final PrivacyPolicy privacyPolicy;
    private final TagService tagService;
    private final NotificationService notificationService;
    private final BadgeService badgeService;
    private final ProjectResponseMapper responseMapper;
    private final ObjectMapper objectMapper;
    private final EditorImageService editorImages;
    private final SubscriptionService subscriptions;
    private final SnapshotLimits snapshotLimits;
    private final SnapshotCapture snapshotCapture;

    public ProjectForkService(AccessGuard accessGuard, ProjectRepository projectRepository,
                               ProjectSnapshotRepository projectSnapshotRepository, TrailRepository trailRepository,
                               TrailItemRepository trailItemRepository, ItemRepository itemRepository,
                               BlockedUserRepository blockedUserRepository,
                               PrivacyPolicy privacyPolicy, TagService tagService,
                               NotificationService notificationService, BadgeService badgeService,
                                ProjectResponseMapper responseMapper, ObjectMapper objectMapper, EditorImageService editorImages,
                                SubscriptionService subscriptions,
                                SnapshotLimits snapshotLimits, SnapshotCapture snapshotCapture) {
        this.accessGuard = accessGuard;
        this.projectRepository = projectRepository;
        this.projectSnapshotRepository = projectSnapshotRepository;
        this.trailRepository = trailRepository;
        this.trailItemRepository = trailItemRepository;
        this.itemRepository = itemRepository;
        this.blockedUserRepository = blockedUserRepository;
        this.privacyPolicy = privacyPolicy;
        this.tagService = tagService;
        this.notificationService = notificationService;
        this.badgeService = badgeService;
        this.responseMapper = responseMapper;
        this.objectMapper = objectMapper;
        this.editorImages = editorImages;
        this.subscriptions = subscriptions;
        this.snapshotLimits = snapshotLimits;
        this.snapshotCapture = snapshotCapture;
    }

    @Transactional
    public ProjectResponseDTO fork(Long sourceProjectId, User requester) {
        subscriptions.lockStorage(requester);
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

        ProjectSnapshotData published = source.getVisibility() == ProjectVisibility.PUBLISHED ? projectSnapshotRepository
                .findLatestPublishByProjectId(sourceProjectId)
                .map(snapshot -> snapshotLimits.read(snapshot.getContent()))
                .orElse(null) : null;
        if (published == null && source.getVisibility() == ProjectVisibility.PUBLISHED) {
            throw new ResourceNotFoundException("Published version unavailable; owner must publish again");
        }
        if (published == null) published = snapshotCapture.capture(sourceProjectId);
        Project fork = new Project();
        fork.setTitle(published.title());
        fork.setDescription(published.description());
        fork.setVisibility(ProjectVisibility.PRIVATE);
        fork.setOwner(requester);
        fork.setForkedFrom(source);
        fork.setForkedFromTitle(published.title());
        fork.setCreationDate(new Date());
        fork.setModifiedDate(new Date());
        List<String> tags = published.tags() == null ? List.of() : List.of(published.tags().split(","));
        tagService.applyProjectTags(fork, tags, requester.getId());
        fork = projectRepository.save(fork);

        forkFromSnapshot(fork, published);

        itemRepository.flush();
        subscriptions.assertUploadAllowed(requester, 0, subscriptions.isSupporter(requester));
        editorImages.copyForkImages(fork);
        notificationService.recordEvent(source.getOwner(), "FORK", source, requester, fork.getCreationDate());
        badgeService.checkAndAwardBadges(source.getOwner());
        return responseMapper.toResponse(fork, responseMapper.liveTagNames(fork));
    }

    private void forkFromSnapshot(Project fork, ProjectSnapshotData data) {
        Map<Long, Item> itemCopies = new HashMap<>();
        int trailOrder = 0;

        for (ProjectSnapshotData.TrailData trailData : data.trails()) {
            ContentLimits.assertTrailDescription(trailData.description());
            Trail trailCopy = new Trail();
            trailCopy.setTitle(trailData.title());
            trailCopy.setOrderIndex(trailOrder++);
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
                membershipCopy = trailItemRepository.save(membershipCopy);
            }
        }

        for (ProjectSnapshotData.ItemData itemData : data.looseItems()) {
            itemCopies.computeIfAbsent(itemData.id(), ignored -> copyItemFromData(itemData, fork, true));
        }
        retargetMentions(itemCopies, objectMapper);

    }

    static void retargetMentions(Map<Long, Item> itemCopies, ObjectMapper objectMapper) {
        if (itemCopies.entrySet().stream().allMatch(entry -> entry.getKey().equals(entry.getValue().getId()))) return;
        for (Item copy : itemCopies.values()) {
            ItemContent content = copy.getContent();
            if (content == null || content.getContent() == null
                    || (!content.getContent().contains("tramo-idea:") && !content.getContent().contains("mypath-idea:"))) continue;
            JsonNode root;
            try { root = objectMapper.readTree(content.getContent()); }
            catch (RuntimeException invalid) { continue; }
            if (retargetMentions(root, itemCopies)) {
                String rewritten = objectMapper.writeValueAsString(root);
                ContentLimits.assertEditorNote(rewritten);
                content.setContent(rewritten);
            }
        }
    }

    private static boolean retargetMentions(JsonNode node, Map<Long, Item> itemCopies) {
        boolean changed = false;
        if (node.isObject() && "link".equals(node.path("type").asText(""))) {
            String rel = node.path("rel").asText("");
            if (rel.startsWith("tramo-idea:") || rel.startsWith("mypath-idea:")) {
                try {
                    Item target = itemCopies.get(Long.parseLong(rel.substring(rel.indexOf(':') + 1)));
                    if (target != null) {
                        ((ObjectNode) node).put("rel", "tramo-idea:" + target.getId());
                        changed = true;
                    }
                } catch (NumberFormatException ignored) { }
            }
        }
        for (JsonNode child : node) changed = retargetMentions(child, itemCopies) || changed;
        return changed;
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
            ContentLimits.assertEditorNote(itemData.content());
            ItemContent contentCopy = new ItemContent();
            contentCopy.setContent(itemData.content());
            contentCopy.setUpdatedDate(new Date());
            copy.setContent(contentCopy);
        }
        return itemRepository.save(copy);
    }
}
