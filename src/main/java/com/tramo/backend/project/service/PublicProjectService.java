package com.tramo.backend.project.service;

import com.tramo.backend.comment.repository.CommentRepository;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.project.dto.PublicItemDTO;
import com.tramo.backend.project.dto.PublicProjectResponseDTO;
import com.tramo.backend.project.dto.PublicTrailDTO;
import com.tramo.backend.project.dto.SitemapProjectDTO;
import com.tramo.backend.project.dto.SitemapUserDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectSnapshot;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.entity.ProjectView;
import com.tramo.backend.project.repository.ProjectBookmarkRepository;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectSnapshotRepository;
import com.tramo.backend.project.repository.ProjectViewRepository;
import com.tramo.backend.project.repository.ProjectVoteRepository;
import com.tramo.backend.project.snapshot.ProjectSnapshotData;
import com.tramo.backend.trail.dto.AssociationDTO;
import com.tramo.backend.trail.entity.Association;
import com.tramo.backend.trail.entity.AssociationTargetType;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.repository.AssociationRepository;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserBadgeRepository;
import com.tramo.backend.user.repository.UserRepository;
import com.tramo.backend.user.service.PrivacyPolicy;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PublicProjectService {
    private final AccessGuard accessGuard;
    private final ProjectRepository projectRepository;
    private final ProjectViewRepository projectViewRepository;
    private final ProjectVoteRepository projectVoteRepository;
    private final ProjectBookmarkRepository projectBookmarkRepository;
    private final ProjectSnapshotRepository projectSnapshotRepository;
    private final TrailRepository trailRepository;
    private final TrailItemRepository trailItemRepository;
    private final ItemRepository itemRepository;
    private final AssociationRepository itemLinkRepository;
    private final CommentRepository commentRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final UserRepository userRepository;
    private final BadgeService badgeService;
    private final ProjectThumbnailResolver thumbnailResolver;
    private final ProjectIdCodec projectIdCodec;
    private final ObjectMapper objectMapper;
    private final PrivacyPolicy privacyPolicy;

    public PublicProjectService(AccessGuard accessGuard, ProjectRepository projectRepository,
                                 ProjectViewRepository projectViewRepository, ProjectVoteRepository projectVoteRepository,
                                 ProjectBookmarkRepository projectBookmarkRepository,
                                 ProjectSnapshotRepository projectSnapshotRepository, TrailRepository trailRepository,
                                 TrailItemRepository trailItemRepository, ItemRepository itemRepository,
                                 AssociationRepository itemLinkRepository,
                                 CommentRepository commentRepository, UserBadgeRepository userBadgeRepository,
                                 UserRepository userRepository, BadgeService badgeService,
                                 ProjectThumbnailResolver thumbnailResolver, ProjectIdCodec projectIdCodec,
                                 ObjectMapper objectMapper, PrivacyPolicy privacyPolicy) {
        this.accessGuard = accessGuard;
        this.projectRepository = projectRepository;
        this.projectViewRepository = projectViewRepository;
        this.projectVoteRepository = projectVoteRepository;
        this.projectBookmarkRepository = projectBookmarkRepository;
        this.projectSnapshotRepository = projectSnapshotRepository;
        this.trailRepository = trailRepository;
        this.trailItemRepository = trailItemRepository;
        this.itemRepository = itemRepository;
        this.itemLinkRepository = itemLinkRepository;
        this.commentRepository = commentRepository;
        this.userBadgeRepository = userBadgeRepository;
        this.userRepository = userRepository;
        this.badgeService = badgeService;
        this.thumbnailResolver = thumbnailResolver;
        this.projectIdCodec = projectIdCodec;
        this.objectMapper = objectMapper;
        this.privacyPolicy = privacyPolicy;
    }

    @Transactional
    public PublicProjectResponseDTO getPublicProject(Long id, User requester, String anonId) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        accessGuard.assertViewable(project, requester);

        String viewerKey = requester != null ? "user:" + requester.getId()
                : anonId != null && !anonId.isBlank() ? "anon:" + anonId
                : null;
        long viewCount = project.getViewCount();
        if (viewerKey != null && !projectViewRepository.existsByProjectIdAndViewerKey(id, viewerKey)) {
            ProjectView view = new ProjectView();
            view.setProject(project);
            view.setViewerKey(viewerKey);
            view.setCreatedDate(new Date());
            projectViewRepository.save(view);
            projectRepository.incrementViewCount(id);
            viewCount++;

            if (project.getVisibility() == ProjectVisibility.PUBLISHED
                    && userBadgeRepository.countByUserIdAndBadgeCodeIn(
                            project.getOwner().getId(), BadgeService.VIEW_BADGE_CODES) < BadgeService.VIEW_BADGE_CODES.size()) {
                long viewsAfter = projectRepository.sumViewCountByOwnerIdAndPublished(project.getOwner().getId());
                if (badgeService.crossedViewBadgeThreshold(viewsAfter - 1, viewsAfter)) {
                    badgeService.checkAndAwardBadges(project.getOwner());
                }
            }
        }

        Optional<ProjectSnapshot> snapshotOpt = project.getVisibility() == ProjectVisibility.PUBLISHED
                ? projectSnapshotRepository.findLatestPublishByProjectIdIn(List.of(id)).stream().findFirst()
                : Optional.empty();

        String title;
        String description;
        Date displayDate;
        List<PublicTrailDTO> trails;
        List<PublicItemDTO> looseItems;
        if (snapshotOpt.isPresent()) {
            ProjectSnapshot snapshot = snapshotOpt.get();
            ProjectSnapshotData data = objectMapper.readValue(snapshot.getContent(), ProjectSnapshotData.class);
            title = data.title();
            description = data.description();
            displayDate = snapshot.getCreatedDate();
            trails = data.trails().stream()
                    .map(t -> new PublicTrailDTO(
                            t.id(), t.title(), t.description(), t.version(),
                            t.forkedFromId() != null ? String.valueOf(t.forkedFromId()) : null,
                            t.items().stream().map(this::toPublicItem).toList()
                    ))
                    .toList();
            looseItems = data.looseItems().stream().map(this::toPublicItem).toList();
        } else {
            List<Trail> projectTrails = trailRepository.findByProjectId(id);
            List<TrailItem> allTrailItems = projectTrails.isEmpty()
                    ? List.of()
                    : trailItemRepository.findByTrailIdInWithItemAndContent(projectTrails.stream().map(Trail::getId).toList());
            Map<Long, List<TrailItem>> itemsByTrailId = allTrailItems.stream()
                    .collect(Collectors.groupingBy(trailItem -> trailItem.getTrail().getId()));

            Set<Long> trailItemIds = allTrailItems.stream()
                    .map(ti -> ti.getItem().getId())
                    .collect(Collectors.toSet());
            List<Item> projectItems = itemRepository.findByProjectId(id);
            Map<Long, Item> projectItemById = projectItems.stream()
                    .collect(Collectors.toMap(Item::getId, item -> item, (a, b) -> a));
            Map<Long, List<Association>> outgoingByItemId = projectItemById.isEmpty()
                    ? Map.of()
                    : itemLinkRepository.findBySourceItemIdIn(projectItemById.keySet()).stream()
                            .collect(Collectors.groupingBy(a -> a.getSourceItem().getId()));

            title = project.getTitle();
            description = project.getDescription();
            displayDate = project.getModifiedDate();
            trails = projectTrails.stream()
                    .map(trail -> new PublicTrailDTO(
                            trail.getId(),
                            trail.getTitle(),
                            trail.getDescription(),
                            trail.getVersion(),
                            trail.getForkedFrom() != null ? String.valueOf(trail.getForkedFrom().getId()) : null,
                            itemsByTrailId.getOrDefault(trail.getId(), List.of()).stream()
                                    .map(ti -> toPublicItem(ti, projectItemById, outgoingByItemId))
                                    .toList()
                    ))
                    .toList();
            looseItems = projectItems.stream()
                    .filter(item -> !trailItemIds.contains(item.getId()))
                    .map(item -> toPublicItem(item, null, null, projectItemById, outgoingByItemId))
                    .toList();
        }

        Project publicSource = project.getForkedFrom();
        return new PublicProjectResponseDTO(
                projectIdCodec.encode(project.getId()),
                title,
                description,
                project.getOwner().getUsername(),
                displayDate,
                thumbnailResolver.resolveThumbnail(project).imageUrl(),
                trails,
                looseItems,
                projectVoteRepository.countByProjectId(id),
                requester != null && projectVoteRepository.findByProjectIdAndUserId(id, requester.getId()).isPresent(),
                requester != null && projectBookmarkRepository.findByProjectIdAndUserId(id, requester.getId()).isPresent(),
                viewCount,
                commentRepository.countGroupedByProjectIdIn(List.of(id)).stream()
                        .findFirst()
                        .map(CommentRepository.ProjectCommentCount::getCommentCount)
                        .orElse(0L),
                project.getVisibility().toJson(),
                publicSource != null ? projectIdCodec.encode(publicSource.getId()) : null,
                publicSource != null ? publicSource.getTitle() : null,
                publicSource != null ? publicSource.getOwner().getUsername() : null,
                privacyPolicy.canFork(project.getOwner()),
                privacyPolicy.canComment(project.getOwner(), requester)
        );
    }

    private PublicItemDTO toPublicItem(ProjectSnapshotData.ItemData item) {
        List<AssociationDTO> associations = item.associations().stream()
                .map(a -> new AssociationDTO(String.valueOf(a.id()), a.type(), a.targetType(),
                        String.valueOf(a.targetId()), a.targetTitle()))
                .toList();
        return new PublicItemDTO(item.id(), item.title(), item.type(), item.content(), item.titleAlign(),
                item.annotation(), item.associationId() != null ? String.valueOf(item.associationId()) : null,
                associations);
    }

    private PublicItemDTO toPublicItem(TrailItem trailItem, Map<Long, Item> projectItemById,
                                        Map<Long, List<Association>> outgoingByItemId) {
        return toPublicItem(trailItem.getItem(), trailItem.getAnnotation(),
                trailItem.getAssociation() != null ? String.valueOf(trailItem.getAssociation().getId()) : null,
                projectItemById, outgoingByItemId);
    }

    private PublicItemDTO toPublicItem(Item item, String annotation, String associationId,
                                        Map<Long, Item> projectItemById,
                                        Map<Long, List<Association>> outgoingByItemId) {
        String content = item.getContent() != null ? item.getContent().getContent() : "";
        List<AssociationDTO> associations = outgoingByItemId.getOrDefault(item.getId(), List.of()).stream()
                .filter(a -> a.getTargetType() == AssociationTargetType.ITEM && projectItemById.containsKey(a.getTargetId()))
                .map(a -> new AssociationDTO(
                        String.valueOf(a.getId()),
                        a.getType().name(),
                        a.getTargetType().name(),
                        String.valueOf(a.getTargetId()),
                        projectItemById.get(a.getTargetId()).getTitle()
                ))
                .toList();
        return new PublicItemDTO(item.getId(), item.getTitle(), item.getType(), content, item.getTitleAlign(),
                annotation, associationId, associations);
    }

    public List<SitemapProjectDTO> getSitemapProjects() {
        return projectRepository.findByVisibilityOrderByLastPublishedDateDesc(ProjectVisibility.PUBLISHED).stream()
                .map(project -> new SitemapProjectDTO(projectIdCodec.encode(project.getId()), project.getModifiedDate()))
                .toList();
    }

    public List<SitemapUserDTO> getSitemapUsers() {
        return userRepository.findByVisibilityTrueAndBannedFalse().stream()
                .map(user -> new SitemapUserDTO(user.getUsername(), user.getUpdatedAt()))
                .toList();
    }
}
