package com.tramo.backend.project.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.dto.ProjectResponseDTO;
import com.tramo.backend.project.dto.ProjectSnapshotDetailDTO;
import com.tramo.backend.project.dto.ProjectSnapshotSummaryDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectSnapshot;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectSnapshotRepository;
import com.tramo.backend.project.snapshot.ProjectSnapshotData;
import com.tramo.backend.trail.entity.Association;
import com.tramo.backend.trail.entity.AssociationTargetType;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.repository.AssociationRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.FollowRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProjectPublishService {
    private final AccessGuard accessGuard;
    private final ProjectRepository projectRepository;
    private final TrailRepository trailRepository;
    private final TrailItemRepository trailItemRepository;
    private final AssociationRepository itemLinkRepository;
    private final ProjectSnapshotRepository projectSnapshotRepository;
    private final FollowRepository followRepository;
    private final NotificationService notificationService;
    private final BadgeService badgeService;
    private final ProjectResponseMapper responseMapper;
    private final ObjectMapper objectMapper;

    public ProjectPublishService(AccessGuard accessGuard, ProjectRepository projectRepository,
                                  TrailRepository trailRepository, TrailItemRepository trailItemRepository,
                                  AssociationRepository itemLinkRepository,
                                  ProjectSnapshotRepository projectSnapshotRepository,
                                  FollowRepository followRepository, NotificationService notificationService,
                                  BadgeService badgeService, ProjectResponseMapper responseMapper,
                                  ObjectMapper objectMapper) {
        this.accessGuard = accessGuard;
        this.projectRepository = projectRepository;
        this.trailRepository = trailRepository;
        this.trailItemRepository = trailItemRepository;
        this.itemLinkRepository = itemLinkRepository;
        this.projectSnapshotRepository = projectSnapshotRepository;
        this.followRepository = followRepository;
        this.notificationService = notificationService;
        this.badgeService = badgeService;
        this.responseMapper = responseMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProjectResponseDTO publish(Long id, User requester) {
        Project project = accessGuard.getOwnedProject(id, requester);
        if (project.getDescription() == null || project.getDescription().isBlank()) {
            throw new IllegalArgumentException("Add a description before publishing");
        }
        ProjectVisibility previousVisibility = project.getVisibility();
        boolean firstPublish = project.getFirstPublishedDate() == null;
        project.setVisibility(ProjectVisibility.PUBLISHED);
        if (firstPublish) {
            project.setFirstPublishedDate(new Date());
        }
        if (previousVisibility != ProjectVisibility.PUBLISHED) {
            project.setLastPublishedDate(firstPublish ? project.getFirstPublishedDate() : new Date());
        }
        project.setModifiedDate(new Date());
        project = projectRepository.save(project);

        trailRepository.bumpVersionsByProjectId(project.getId());
        project = projectRepository.findById(project.getId()).orElseThrow();
        createSnapshot(project, "PUBLISH");
        badgeService.checkAndAwardBadges(project.getOwner());
        if (firstPublish) {
            notifyFollowers(project.getOwner(), "PUBLISH", project);
        }
        return responseMapper.toResponse(project, responseMapper.liveTagNames(project));
    }

    void applyVisibilityTransition(Project project, ProjectVisibility previousVisibility, boolean firstPublish,
                                    Date firstPublishTimestamp) {
        badgeService.checkAndAwardBadges(project.getOwner());
        if (previousVisibility != ProjectVisibility.PUBLISHED) {
            trailRepository.bumpVersionsByProjectId(project.getId());
            project = projectRepository.findById(project.getId()).orElseThrow();
            createSnapshot(project, "PUBLISH");
            project.setLastPublishedDate(firstPublish ? firstPublishTimestamp : new Date());
        }

        if (previousVisibility != ProjectVisibility.PUBLISHED && firstPublish) {
            notifyFollowers(project.getOwner(), "PUBLISH", project);
        }
    }

    @Transactional
    public void shareProject(Long id, User sharer) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        accessGuard.assertViewable(project, sharer);
        notifyFollowers(sharer, "SHARE", project);
    }

    private void notifyFollowers(User actor, String type, Project project) {
        notificationService.recordEventForAll(
                followRepository.findFollowersByFollowedId(actor.getId()), type, project, actor);
    }

    void createSnapshot(Project project, String trigger) {
        List<Trail> projectTrails = trailRepository.findByProjectId(project.getId());
        List<Long> trailIds = projectTrails.stream().map(Trail::getId).toList();

        Map<Long, List<TrailItem>> membershipsByTrailId = trailIds.isEmpty() ? Map.of()
                : trailItemRepository.findByTrailIdInWithItemContentAndAssociation(trailIds).stream()
                        .collect(Collectors.groupingBy(ti -> ti.getTrail().getId(), LinkedHashMap::new, Collectors.toList()));

        Map<Long, Item> itemById = membershipsByTrailId.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(ti -> ti.getItem().getId(), TrailItem::getItem, (a, b) -> a));
        Map<Long, List<Association>> outgoingByItemId = itemById.isEmpty() ? Map.of()
                : itemLinkRepository.findBySourceItemIdIn(itemById.keySet()).stream()
                        .collect(Collectors.groupingBy(a -> a.getSourceItem().getId()));

        List<ProjectSnapshotData.TrailData> trails = new ArrayList<>();
        for (Trail trail : projectTrails) {
            List<ProjectSnapshotData.ItemData> items = new ArrayList<>();
            for (TrailItem membership : membershipsByTrailId.getOrDefault(trail.getId(), List.of())) {
                Item item = membership.getItem();
                Association assoc = membership.getAssociation();
                List<ProjectSnapshotData.AssociationData> associations = outgoingByItemId
                        .getOrDefault(item.getId(), List.of()).stream()
                        .filter(a -> a.getTargetType() == AssociationTargetType.ITEM && itemById.containsKey(a.getTargetId()))
                        .map(a -> new ProjectSnapshotData.AssociationData(a.getId(), a.getType().name(),
                                a.getTargetType().name(), a.getTargetId(), itemById.get(a.getTargetId()).getTitle()))
                        .toList();
                items.add(new ProjectSnapshotData.ItemData(item.getId(), item.getTitle(), item.getType(),
                        item.getTitleAlign(), item.getContent() != null ? item.getContent().getContent() : null,
                        membership.getAnnotation(), assoc != null ? assoc.getId() : null, associations));
            }
            trails.add(new ProjectSnapshotData.TrailData(trail.getId(), trail.getTitle(), trail.getDescription(),
                    trail.getVisibility(), trail.getVersion(),
                    trail.getForkedFrom() != null ? trail.getForkedFrom().getId() : null, items));
        }

        ProjectSnapshotData data = new ProjectSnapshotData(ProjectSnapshotData.CURRENT_SCHEMA_VERSION,
                project.getId(), project.getTitle(), project.getDescription(),
                project.getVisibility().toJson(), project.getThumbnailImageUrl(),
                String.join(",", responseMapper.liveTagNames(project)), trails);

        ProjectSnapshot snapshot = new ProjectSnapshot();
        snapshot.setProject(project);
        snapshot.setTrigger(trigger);
        if ("PUBLISH".equals(trigger)) {
            snapshot.setVersion(projectSnapshotRepository.findMaxVersion(project.getId()).orElse(0) + 1);
        }
        snapshot.setContent(objectMapper.writeValueAsString(data));
        snapshot.setCreatedDate(new Date());
        projectSnapshotRepository.save(snapshot);
    }

    @Transactional
    public void backfillMissingPublishSnapshots() {
        Set<Long> alreadySnapshotted = Set.copyOf(projectSnapshotRepository.findProjectIdsWithPublishSnapshot());
        for (Project project : projectRepository.findByVisibilityOrderByLastPublishedDateDesc(ProjectVisibility.PUBLISHED)) {
            if (!alreadySnapshotted.contains(project.getId())) {
                createSnapshot(project, "PUBLISH");
            }
        }
    }

    public List<ProjectSnapshotSummaryDTO> listSnapshots(Long id, User requester) {
        accessGuard.getOwnedProject(id, requester);
        return projectSnapshotRepository.findByProjectIdAndTriggerOrderByVersionDesc(id, "PUBLISH").stream()
                .map(s -> new ProjectSnapshotSummaryDTO(s.getId(), s.getVersion(), s.getCreatedDate()))
                .toList();
    }

    public ProjectSnapshotDetailDTO getSnapshotDetail(Long id, Long snapshotId, User requester) {
        accessGuard.getOwnedProject(id, requester);
        ProjectSnapshot snapshot = projectSnapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new ResourceNotFoundException("Version not found"));
        if (!snapshot.getProject().getId().equals(id)) {
            throw new ResourceNotFoundException("Version not found");
        }
        ProjectSnapshotData content = objectMapper.readValue(snapshot.getContent(), ProjectSnapshotData.class);
        return new ProjectSnapshotDetailDTO(snapshot.getId(), snapshot.getVersion(), snapshot.getCreatedDate(), content);
    }

    public ProjectSnapshotDetailDTO getPublicSnapshotDetail(Long id, Long snapshotId, User requester) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Version not found"));
        accessGuard.assertViewable(project, requester);
        if (project.getFirstPublishedDate() == null) {
            throw new ResourceNotFoundException("Version not found");
        }
        ProjectSnapshot snapshot = projectSnapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new ResourceNotFoundException("Version not found"));
        if (!snapshot.getProject().getId().equals(id) || !"PUBLISH".equals(snapshot.getTrigger())) {
            throw new ResourceNotFoundException("Version not found");
        }
        ProjectSnapshotData content = objectMapper.readValue(snapshot.getContent(), ProjectSnapshotData.class);
        return new ProjectSnapshotDetailDTO(snapshot.getId(), snapshot.getVersion(), snapshot.getCreatedDate(), content);
    }
}
