package com.tramo.backend.project.service;

import com.tramo.backend.auth.service.MinAgeValidator;
import com.tramo.backend.comment.repository.CommentRepository;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.upload.R2Client;
import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.moderation.repository.CommentReportRepository;
import com.tramo.backend.moderation.repository.ProjectReportRepository;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.tag.entity.Tag;
import com.tramo.backend.tag.service.TagService;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.ItemContent;
import com.tramo.backend.trail.entity.Association;
import com.tramo.backend.trail.entity.AssociationTargetType;
import com.tramo.backend.trail.entity.AssociationType;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.dto.AssociationDTO;
import com.tramo.backend.trail.entity.ItemImageReference;
import com.tramo.backend.trail.repository.AssociationRepository;
import com.tramo.backend.trail.repository.ItemImageReferenceRepository;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.project.dto.ActivityItemDTO;
import com.tramo.backend.project.dto.AuthorCountDTO;
import com.tramo.backend.project.dto.BadgeDTO;
import com.tramo.backend.project.dto.BookmarkResponseDTO;
import com.tramo.backend.project.dto.ExploreBundleDTO;
import com.tramo.backend.project.dto.FollowResponseDTO;
import com.tramo.backend.project.dto.FollowUserDTO;
import com.tramo.backend.project.dto.ForkFeedItemDTO;
import com.tramo.backend.project.dto.GraphPreviewDTO;
import com.tramo.backend.project.dto.PageResponseDTO;
import com.tramo.backend.project.dto.ProfileStatsBundleDTO;
import com.tramo.backend.project.dto.ProfileStatsDTO;
import com.tramo.backend.project.dto.ProjectFeedItemDTO;
import com.tramo.backend.project.dto.ProjectImageDTO;
import com.tramo.backend.project.dto.ProjectRequestDTO;
import com.tramo.backend.project.dto.ProjectResponseDTO;
import com.tramo.backend.project.dto.SetThumbnailRequestDTO;
import com.tramo.backend.project.dto.ProjectSnapshotDetailDTO;
import com.tramo.backend.project.dto.ProjectSnapshotSummaryDTO;
import com.tramo.backend.project.dto.PublicItemDTO;
import com.tramo.backend.project.dto.PublicTrailDTO;
import com.tramo.backend.project.dto.PublicProfileDTO;
import com.tramo.backend.project.dto.PublicProjectResponseDTO;
import com.tramo.backend.project.dto.SitemapProjectDTO;
import com.tramo.backend.project.dto.SitemapUserDTO;
import com.tramo.backend.project.dto.TagCountDTO;
import com.tramo.backend.project.dto.UpdateProfileRequestDTO;
import com.tramo.backend.project.dto.UserProfileDTO;
import com.tramo.backend.project.dto.VoteResponseDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectBookmark;
import com.tramo.backend.project.entity.ProjectSnapshot;
import com.tramo.backend.project.entity.ProjectThumbnailType;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.entity.ProjectVote;
import com.tramo.backend.project.entity.ProjectView;
import com.tramo.backend.project.repository.ProjectBookmarkRepository;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectSnapshotRepository;
import com.tramo.backend.project.repository.ProjectViewRepository;
import com.tramo.backend.project.repository.ProjectVoteRepository;
import com.tramo.backend.project.snapshot.ProjectSnapshotData;
import com.tramo.backend.user.entity.Follow;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.entity.UserBadge;
import com.tramo.backend.user.repository.FollowRepository;
import com.tramo.backend.user.repository.BlockedUserRepository;
import com.tramo.backend.user.repository.UserBadgeRepository;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProjectService {
    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final long ON_THE_MAP_VIEW_THRESHOLD = 1000;
    private static final long TRENDSETTER_VIEW_THRESHOLD = 10000;
    private static final long EXPLORE_CACHE_REFRESH_MS = 5 * 60 * 1000;

    private volatile List<TagCountDTO> cachedHotTopics = List.of();
    private volatile List<AuthorCountDTO> cachedActiveAuthors = List.of();

    private final ProjectRepository projectRepository;
    private final TrailRepository trailRepository;
    private final TrailItemRepository trailItemRepository;
    private final ItemRepository itemRepository;
    private final ProjectVoteRepository projectVoteRepository;
    private final ProjectBookmarkRepository projectBookmarkRepository;
    private final ProjectViewRepository projectViewRepository;
    private final AssociationRepository itemLinkRepository;
    private final ItemImageReferenceRepository itemImageReferenceRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final BlockedUserRepository blockedUserRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final NotificationService notificationService;
    private final ProjectReportRepository projectReportRepository;
    private final CommentRepository commentRepository;
    private final CommentReportRepository commentReportRepository;
    private final ProjectIdCodec projectIdCodec;
    private final R2Client r2Client;
    private final SubscriptionService subscriptionService;
    private final UploadRecordRepository uploadRecordRepository;
    private final ProjectSnapshotRepository projectSnapshotRepository;
    private final ObjectMapper objectMapper;
    private final TagService tagService;
    private final MinAgeValidator minAgeValidator;

    public ProjectService(ProjectRepository projectRepository, TrailRepository trailRepository,
                           TrailItemRepository trailItemRepository, ItemRepository itemRepository,
                           ProjectVoteRepository projectVoteRepository, ProjectBookmarkRepository projectBookmarkRepository,
                           ProjectViewRepository projectViewRepository, AssociationRepository itemLinkRepository,
                           ItemImageReferenceRepository itemImageReferenceRepository,
                           UserRepository userRepository, FollowRepository followRepository,
                           BlockedUserRepository blockedUserRepository,
                           UserBadgeRepository userBadgeRepository, NotificationService notificationService,
                           ProjectReportRepository projectReportRepository, CommentRepository commentRepository,
                           CommentReportRepository commentReportRepository, ProjectIdCodec projectIdCodec,
                           R2Client r2Client, SubscriptionService subscriptionService,
                           UploadRecordRepository uploadRecordRepository,
                           ProjectSnapshotRepository projectSnapshotRepository, ObjectMapper objectMapper,
                           TagService tagService, MinAgeValidator minAgeValidator) {
        this.minAgeValidator = minAgeValidator;
        this.tagService = tagService;
        this.subscriptionService = subscriptionService;
        this.uploadRecordRepository = uploadRecordRepository;
        this.projectSnapshotRepository = projectSnapshotRepository;
        this.objectMapper = objectMapper;
        this.projectRepository = projectRepository;
        this.trailRepository = trailRepository;
        this.trailItemRepository = trailItemRepository;
        this.itemRepository = itemRepository;
        this.itemLinkRepository = itemLinkRepository;
        this.itemImageReferenceRepository = itemImageReferenceRepository;
        this.projectViewRepository = projectViewRepository;
        this.projectVoteRepository = projectVoteRepository;
        this.projectBookmarkRepository = projectBookmarkRepository;
        this.userRepository = userRepository;
        this.followRepository = followRepository;
        this.blockedUserRepository = blockedUserRepository;
        this.userBadgeRepository = userBadgeRepository;
        this.notificationService = notificationService;
        this.commentRepository = commentRepository;
        this.commentReportRepository = commentReportRepository;
        this.projectReportRepository = projectReportRepository;
        this.projectIdCodec = projectIdCodec;
        this.r2Client = r2Client;
    }

    @Transactional
    public ProjectResponseDTO create(ProjectRequestDTO request, User owner) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        Project project = new Project();
        project.setTitle(request.getTitle());
        project.setDescription(request.getDescription());
        project.setVisibility(request.getVisibility());
        project.setOwner(owner);
        project.setCreationDate(new Date());
        project.setModifiedDate(new Date());
        tagService.applyProjectTags(project, normalizeTagNames(request.getTags()), owner.getId());
        Project saved = projectRepository.save(project);
        return toResponse(saved, liveTagNames(saved));
    }

    public List<ProjectResponseDTO> getAllForUser(User owner) {
        List<Project> projects = projectRepository.findByOwnerId(owner.getId());
        List<Long> projectIds = projects.stream().map(Project::getId).toList();
        Map<Long, Long> imageBytesByProjectId = uploadRecordRepository.sumBytesGroupedByProjectIdIn(projectIds).stream()
                .collect(Collectors.toMap(UploadRecordRepository.ProjectBytesSum::getProjectId, UploadRecordRepository.ProjectBytesSum::getBytes));
        Map<Long, Long> contentBytesByProjectId = itemRepository.sumContentBytesGroupedByProjectIdIn(projectIds).stream()
                .collect(Collectors.toMap(ItemRepository.ProjectContentBytesSum::getProjectId, ItemRepository.ProjectContentBytesSum::getBytes));
        Map<Long, List<String>> tagNamesByProjectId = tagNamesGroupedByProjectId(projectIds);
        Map<Long, ThumbnailResolution> thumbnailByProjectId = resolveThumbnails(projects);
        return projects.stream()
                .map(p -> toResponse(p, imageBytesByProjectId.getOrDefault(p.getId(), 0L)
                        + contentBytesByProjectId.getOrDefault(p.getId(), 0L),
                        tagNamesByProjectId.getOrDefault(p.getId(), List.of()),
                        thumbnailByProjectId.getOrDefault(p.getId(), ThumbnailResolution.EMPTY)))
                .toList();
    }

    public ProjectResponseDTO getById(Long id, User requester) {
        return toResponse(getOwnedProject(id, requester));
    }

    @Transactional
    public ProjectResponseDTO update(Long id, ProjectRequestDTO request, User requester) {
        Project project = getOwnedProject(id, requester);
        ProjectVisibility previousVisibility = project.getVisibility();
        boolean touchesModifiedDate = false;
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            project.setTitle(request.getTitle());
            touchesModifiedDate = true;
        }
        if (request.getDescription() != null) {
            project.setDescription(request.getDescription());
            touchesModifiedDate = true;
        }
        boolean firstPublish = false;
        Date firstPublishTimestamp = null;
        if (request.getVisibility() != null) {
            if (request.getVisibility() == ProjectVisibility.PUBLISHED
                    && (project.getDescription() == null || project.getDescription().isBlank())) {
                throw new IllegalArgumentException("Add a description before publishing");
            }
            project.setVisibility(request.getVisibility());
            if (request.getVisibility() == ProjectVisibility.PUBLISHED && project.getFirstPublishedDate() == null) {
                firstPublishTimestamp = new Date();
                project.setFirstPublishedDate(firstPublishTimestamp);
                firstPublish = true;
            }
            touchesModifiedDate = true;
        }
        if (request.getTags() != null) {
            tagService.applyProjectTags(project, normalizeTagNames(request.getTags()), requester.getId());
            touchesModifiedDate = true;
        }
        if (touchesModifiedDate) {
            project.setModifiedDate(new Date());
        }
        Project savedProject = projectRepository.save(project);
        ProjectResponseDTO response = toResponse(savedProject, liveTagNames(savedProject));
        if (request.getVisibility() == ProjectVisibility.PUBLISHED) {
            checkAndAwardBadges(project.getOwner());
            if (previousVisibility != ProjectVisibility.PUBLISHED) {


                for (Trail trail : trailRepository.findByProjectId(project.getId())) {
                    trail.setVersion(trail.getVersion() + 1);
                    trailRepository.save(trail);
                }
                createSnapshot(project, "PUBLISH");
                project.setLastPublishedDate(firstPublish ? firstPublishTimestamp : new Date());
            }


            if (previousVisibility != ProjectVisibility.PUBLISHED && firstPublish) {
                notifyFollowers(project.getOwner(), "PUBLISH", project);
            }
        }
        return response;
    }

    @Transactional
    public ProjectResponseDTO publish(Long id, User requester) {
        Project project = getOwnedProject(id, requester);
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

        for (Trail trail : trailRepository.findByProjectId(project.getId())) {
            trail.setVersion(trail.getVersion() + 1);
            trailRepository.save(trail);
        }
        createSnapshot(project, "PUBLISH");
        checkAndAwardBadges(project.getOwner());
        if (firstPublish) {
            notifyFollowers(project.getOwner(), "PUBLISH", project);
        }
        return toResponse(project, liveTagNames(project));
    }

    // Thumbnail choice is only ever set here (the publish flow) — it's sticky afterwards,
    // read live by resolveThumbnail() regardless of later edits or unpublishing.
    @Transactional
    public ProjectResponseDTO setThumbnail(Long id, SetThumbnailRequestDTO request, User requester) {
        Project project = getOwnedProject(id, requester);
        ProjectThumbnailType type = ProjectThumbnailType.valueOf(request.getType());
        ProjectThumbnailType previousType = project.getThumbnailType();
        String previousImageUrl = project.getThumbnailImageUrl();

        project.setThumbnailType(type);
        project.setThumbnailImageUrl(null);
        project.setThumbnailTrail(null);

        switch (type) {
            case GRAPH -> {
                if (request.getTrailId() == null) {
                    throw new IllegalArgumentException("trailId is required for GRAPH thumbnail");
                }
                Trail trail = trailRepository.findById(Long.valueOf(request.getTrailId()))
                        .orElseThrow(() -> new ResourceNotFoundException("Trail not found"));
                if (!trail.getProject().getId().equals(project.getId())) {
                    throw new AccessDeniedException("Trail does not belong to this project");
                }
                project.setThumbnailTrail(trail);
            }
            case PROJECT_IMAGE, DEDICATED -> {
                if (request.getImageUrl() == null || request.getImageUrl().isBlank()) {
                    throw new IllegalArgumentException("imageUrl is required for this thumbnail type");
                }
                project.setThumbnailImageUrl(request.getImageUrl());
            }
            case NONE -> {
            }
        }

        Project saved = projectRepository.save(project);
        // Only DEDICATED uploads are thumbnail-exclusive — PROJECT_IMAGE URLs point at
        // images still embedded in item content, so those must never be deleted here.
        if (previousType == ProjectThumbnailType.DEDICATED && previousImageUrl != null
                && !previousImageUrl.equals(saved.getThumbnailImageUrl())) {
            r2Client.deleteByPublicUrl(previousImageUrl);
        }
        return toResponse(saved, liveTagNames(saved));
    }

    public List<ProjectImageDTO> listProjectImages(Long id, User requester) {
        Project project = getOwnedProject(id, requester);
        return itemImageReferenceRepository.findByProjectIdOrderByItemIdAsc(project.getId()).stream()
                .map(ref -> new ProjectImageDTO(ref.getUrl(), String.valueOf(ref.getItem().getId()), ref.getItem().getTitle()))
                .toList();
    }

    @Transactional
    public void shareProject(Long id, User sharer) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        assertViewable(project);
        notifyFollowers(sharer, "SHARE", project);
    }

    private void notifyFollowers(User actor, String type, Project project) {
        for (User follower : followRepository.findFollowersByFollowedId(actor.getId())) {
            notificationService.recordEvent(follower, type, project, actor);
        }
    }

    @Transactional
    public void delete(Long id, User requester) {
        Project project = getOwnedProject(id, requester);
        tagService.applyProjectTags(project, List.of(), requester.getId());
        for (Trail trail : trailRepository.findByProjectId(id)) {
            List<TrailItem> memberships = trailItemRepository.findByTrailIdOrderByOrderIndexAsc(trail.getId());
            for (TrailItem membership : memberships) {
                Long itemId = membership.getItem().getId();
                trailItemRepository.delete(membership);
                if (trailItemRepository.findByItemId(itemId).isEmpty()) {
                    itemLinkRepository.deleteBySourceItemId(itemId);
                    itemLinkRepository.deleteByTargetTypeAndTargetId(AssociationTargetType.ITEM, itemId);
                    itemRepository.deleteById(itemId);
                }
            }
            itemLinkRepository.deleteByTargetTypeAndTargetId(AssociationTargetType.TRAIL, trail.getId());
            trailRepository.delete(trail);
        }
        
        for (com.tramo.backend.trail.entity.Item item : itemRepository.findByProjectId(id)) {
            itemLinkRepository.deleteBySourceItemId(item.getId());
            itemLinkRepository.deleteByTargetTypeAndTargetId(AssociationTargetType.ITEM, item.getId());
            trailItemRepository.deleteAll(trailItemRepository.findByItemId(item.getId()));
            itemRepository.delete(item);
        }
        projectVoteRepository.deleteByProjectId(id);
        projectBookmarkRepository.deleteByProjectId(id);
        projectViewRepository.deleteByProjectId(id);
        notificationService.deleteAllForProject(id);
        projectReportRepository.deleteByProjectId(id);
        projectSnapshotRepository.deleteByProjectId(id);
        List<Long> commentIds = commentRepository.findIdsByProjectId(id);
        if (!commentIds.isEmpty()) {
            commentReportRepository.deleteByCommentIdIn(commentIds);
        }
        commentRepository.clearParentReferencesForProject(id);
        commentRepository.deleteByProjectId(id);
        projectRepository.clearForkedFromReferences(id);
        projectRepository.delete(project);
    }

    private void assertViewable(Project project) {
        ProjectVisibility visibility = project.getVisibility();
        if (visibility != ProjectVisibility.UNLISTED && visibility != ProjectVisibility.PUBLISHED) {
            throw new ResourceNotFoundException("Project not found");
        }
    }

    @Transactional
    public ProjectResponseDTO fork(Long sourceProjectId, User requester) {
        Project source = projectRepository.findById(sourceProjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        assertViewable(source);
        if (source.getOwner().getId().equals(requester.getId())) {
            throw new AccessDeniedException("Cannot fork your own project");
        }
        if (blockedUserRepository.existsEitherDirection(requester.getId(), source.getOwner().getId())) {
            throw new AccessDeniedException("Cannot fork this project");
        }

        Project fork = new Project();
        fork.setTitle(source.getTitle());
        fork.setDescription(source.getDescription());
        fork.setVisibility(ProjectVisibility.PRIVATE);
        fork.setOwner(requester);
        fork.setForkedFrom(source);
        fork.setCreationDate(new Date());
        fork.setModifiedDate(new Date());
        tagService.applyProjectTags(fork, liveTagNames(source), requester.getId());
        fork = projectRepository.save(fork);

        Optional<ProjectSnapshot> latestPublish = projectSnapshotRepository
                .findLatestPublishByProjectIdIn(List.of(sourceProjectId)).stream().findFirst();
        if (latestPublish.isPresent()) {
            forkFromSnapshot(fork, latestPublish.get());
        } else {
            forkFromLiveTables(fork, sourceProjectId);
        }

        notificationService.recordEvent(source.getOwner(), "FORK", source, requester);
        checkAndAwardBadges(source.getOwner());
        return toResponse(fork, liveTagNames(fork));
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
                        ignored -> copyItem(membership.getItem()));

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

    private Item copyItem(Item source) {
        Item copy = new Item();
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
                Item itemCopy = itemCopies.computeIfAbsent(itemData.id(), ignored -> copyItemFromData(itemData));

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

        Map<Long, Association> assocCopies = new HashMap<>();
        for (ProjectSnapshotData.TrailData trailData : data.trails()) {
            for (ProjectSnapshotData.ItemData itemData : trailData.items()) {
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

    private Item copyItemFromData(ProjectSnapshotData.ItemData itemData) {
        Item copy = new Item();
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

    private void createSnapshot(Project project, String trigger) {
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
                project.getVisibility().toJson(), project.getThumbnailImageUrl(), String.join(",", liveTagNames(project)), trails);

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
        getOwnedProject(id, requester);
        return projectSnapshotRepository.findByProjectIdAndTriggerOrderByVersionDesc(id, "PUBLISH").stream()
                .map(s -> new ProjectSnapshotSummaryDTO(s.getId(), s.getVersion(), s.getCreatedDate()))
                .toList();
    }

    public ProjectSnapshotDetailDTO getSnapshotDetail(Long id, Long snapshotId, User requester) {
        getOwnedProject(id, requester);
        ProjectSnapshot snapshot = projectSnapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new ResourceNotFoundException("Version not found"));
        if (!snapshot.getProject().getId().equals(id)) {
            throw new ResourceNotFoundException("Version not found");
        }
        ProjectSnapshotData content = objectMapper.readValue(snapshot.getContent(), ProjectSnapshotData.class);
        return new ProjectSnapshotDetailDTO(snapshot.getId(), snapshot.getVersion(), snapshot.getCreatedDate(), content);
    }

    public ProjectSnapshotDetailDTO getPublicSnapshotDetail(Long id, Long snapshotId) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Version not found"));
        assertViewable(project);
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

    @Transactional
    public PublicProjectResponseDTO getPublicProject(Long id, User requester, String anonId) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        assertViewable(project);

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

            if (project.getVisibility() == ProjectVisibility.PUBLISHED) {
                long viewsAfter = projectRepository.sumViewCountByOwnerIdAndPublished(project.getOwner().getId());
                if (crossedViewBadgeThreshold(viewsAfter - 1, viewsAfter)) {
                    checkAndAwardBadges(project.getOwner());
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
        } else {
            
            List<Trail> projectTrails = trailRepository.findByProjectId(id);
            List<TrailItem> allTrailItems = projectTrails.isEmpty()
                    ? List.of()
                    : trailItemRepository.findByTrailIdInWithItemAndContent(projectTrails.stream().map(Trail::getId).toList());
            Map<Long, List<TrailItem>> itemsByTrailId = allTrailItems.stream()
                    .collect(Collectors.groupingBy(trailItem -> trailItem.getTrail().getId()));

            
            
            
            Map<Long, Item> projectItemById = allTrailItems.stream()
                    .collect(Collectors.toMap(ti -> ti.getItem().getId(), TrailItem::getItem, (a, b) -> a));
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
        }

        return new PublicProjectResponseDTO(
                projectIdCodec.encode(project.getId()),
                title,
                description,
                project.getOwner().getUsername(),
                displayDate,
                resolveThumbnail(project).imageUrl(),
                trails,
                projectVoteRepository.countByProjectId(id),
                requester != null && projectVoteRepository.findByProjectIdAndUserId(id, requester.getId()).isPresent(),
                requester != null && projectBookmarkRepository.findByProjectIdAndUserId(id, requester.getId()).isPresent(),
                viewCount,
                commentRepository.countGroupedByProjectIdIn(List.of(id)).stream()
                        .findFirst()
                        .map(CommentRepository.ProjectCommentCount::getCommentCount)
                        .orElse(0L),
                project.getVisibility().toJson()
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

    public ExploreBundleDTO getExploreBundle(String query, String sort, int page, int size, User requester) {
        String q = query == null ? "" : query.trim().toLowerCase();
        // Trigram indexes need >=3 chars to generate tokens; shorter terms can't use
        // idx_project_title_trgm/idx_project_description_trgm/idx_tag_name_trgm and
        // fall back to a full scan on `project`. Treat them like an empty query —
        // the JPQL below already short-circuits filtering on `:query = ''`.
        if (q.length() < 3) q = "";
        Pageable pageable = PageRequest.of(page, size);

        List<Project> pageProjects;
        boolean hasMore;
        if ("hot".equals(sort)) {
            Page<Long> idPage = projectRepository.findPublishedHotIds(ProjectVisibility.PUBLISHED, q, pageable);
            List<Long> ids = idPage.getContent();
            Map<Long, Project> byId = ids.isEmpty()
                    ? Map.of()
                    : projectRepository.findAllByIdInWithFetch(ids).stream()
                        .collect(Collectors.toMap(Project::getId, p -> p));
            pageProjects = ids.stream().map(byId::get).toList();
            hasMore = idPage.hasNext();
        } else if ("following".equals(sort)) {
            List<Long> followedIds = requester == null ? List.of() : followRepository.findFollowedIds(requester.getId());
            if (followedIds.isEmpty()) {
                pageProjects = List.of();
                hasMore = false;
            } else {
                Page<Project> projectPage = projectRepository.findPublishedRecentByOwners(ProjectVisibility.PUBLISHED, followedIds, q, pageable);
                pageProjects = projectPage.getContent();
                hasMore = projectPage.hasNext();
            }
        } else {
            Page<Project> projectPage = projectRepository.findPublishedRecent(ProjectVisibility.PUBLISHED, q, pageable);
            pageProjects = projectPage.getContent();
            hasMore = projectPage.hasNext();
        }

        FeedContext ctx = FeedContext.forPublishedProjects(pageProjects, requester, projectRepository, projectVoteRepository,
                projectBookmarkRepository, commentRepository, projectSnapshotRepository)
                .withThumbnails(resolveThumbnails(pageProjects));
        List<ProjectFeedItemDTO> feed = pageProjects.stream()
                .map(project -> toFeedItem(project, ctx))
                .toList();

        ProjectFeedItemDTO featured = null;
        List<TagCountDTO> hotTopics = List.of();
        List<AuthorCountDTO> activeAuthors = List.of();
        if (page == 0) {
            if (!"following".equals(sort)) {
                featured = projectRepository.findByFeaturedTrue()
                        .filter(project -> project.getVisibility() == ProjectVisibility.PUBLISHED)
                        .map(project -> toFeedItem(project, FeedContext.forPublishedProjects(List.of(project), requester, projectRepository,
                                projectVoteRepository, projectBookmarkRepository, commentRepository, projectSnapshotRepository)
                                .withThumbnails(resolveThumbnails(List.of(project)))))
                        .orElse(null);
            }
            hotTopics = cachedHotTopics;
            activeAuthors = cachedActiveAuthors;
        }

        return new ExploreBundleDTO(feed, hasMore, featured, hotTopics, activeAuthors);
    }

    public List<AuthorCountDTO> getActiveAuthors(int limit) {
        return projectRepository.findActiveAuthors(ProjectVisibility.PUBLISHED, PageRequest.of(0, limit)).stream()
                .map(a -> new AuthorCountDTO(a.getUsername(), a.getAvatar(), a.getCount()))
                .toList();
    }

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void refreshFeaturedProject() {
        List<Project> published = projectRepository.findByVisibilityOrderByLastPublishedDateDesc(ProjectVisibility.PUBLISHED);
        if (published.isEmpty()) return;

        List<Long> ids = published.stream().map(Project::getId).toList();
        Map<Long, Long> rawCounts = new HashMap<>();
        Map<Long, Long> trustedCounts = new HashMap<>();
        Map<Long, Set<String>> seenIps = new HashMap<>();
        Map<Long, Set<String>> seenDevices = new HashMap<>();
        for (ProjectVoteRepository.VoteMeta vote : projectVoteRepository.findMetaByProjectIdIn(ids)) {
            Long projectId = vote.getProjectId();
            rawCounts.merge(projectId, 1L, Long::sum);
            boolean freshIp = vote.getVoterIp() == null
                    || seenIps.computeIfAbsent(projectId, k -> new HashSet<>()).add(vote.getVoterIp());
            boolean freshDevice = vote.getDeviceId() == null
                    || seenDevices.computeIfAbsent(projectId, k -> new HashSet<>()).add(vote.getDeviceId());
            if (freshIp && freshDevice) {
                trustedCounts.merge(projectId, 1L, Long::sum);
            }
        }

        Project top = published.stream()
                .max(Comparator.comparingLong(p -> trustedCounts.getOrDefault(p.getId(), 0L)))
                .orElseThrow();
        log.info("Featured pick: project {} with {} trusted votes ({} raw)",
                top.getId(), trustedCounts.getOrDefault(top.getId(), 0L), rawCounts.getOrDefault(top.getId(), 0L));

        Optional<Project> currentlyFeatured = projectRepository.findByFeaturedTrue();
        if (currentlyFeatured.isPresent() && currentlyFeatured.get().getId().equals(top.getId())) {
            return;
        }

        currentlyFeatured.ifPresent(project -> {
            project.setFeatured(false);
            projectRepository.save(project);
        });
        top.setFeatured(true);
        projectRepository.save(top);
        notificationService.recordFeatured(top.getOwner(), top);
    }

    @PostConstruct
    @Scheduled(fixedRate = EXPLORE_CACHE_REFRESH_MS)
    public void refreshExploreCache() {
        cachedHotTopics = getHotTopics(5);
        cachedActiveAuthors = getActiveAuthors(5);
    }

    @Transactional
    public VoteResponseDTO toggleVote(Long projectId, User requester, String voterIp, String deviceId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        var existingVote = projectVoteRepository.findByProjectIdAndUserId(projectId, requester.getId());
        boolean voted;
        if (existingVote.isPresent()) {
            projectVoteRepository.delete(existingVote.get());
            voted = false;
        } else {
            assertViewable(project);
            ProjectVote vote = new ProjectVote();
            vote.setProject(project);
            vote.setUser(requester);
            vote.setCreatedDate(new Date());
            vote.setVoterIp(voterIp);
            vote.setDeviceId(deviceId);
            projectVoteRepository.save(vote);
            voted = true;
            notificationService.recordEvent(project.getOwner(), "UPVOTE", project, requester);
            checkAndAwardBadges(project.getOwner());
        }
        return new VoteResponseDTO(voted, projectVoteRepository.countByProjectId(projectId));
    }

    @Transactional
    public BookmarkResponseDTO toggleBookmark(Long projectId, User requester) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        var existingBookmark = projectBookmarkRepository.findByProjectIdAndUserId(projectId, requester.getId());
        boolean bookmarked;
        if (existingBookmark.isPresent()) {
            projectBookmarkRepository.delete(existingBookmark.get());
            bookmarked = false;
        } else {
            assertViewable(project);
            ProjectBookmark bookmark = new ProjectBookmark();
            bookmark.setProject(project);
            bookmark.setUser(requester);
            bookmark.setCreatedDate(new Date());
            projectBookmarkRepository.save(bookmark);
            bookmarked = true;
        }
        return new BookmarkResponseDTO(bookmarked);
    }

    public UserProfileDTO getProfile(User user) {
        User fresh = userRepository.findById(user.getId()).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return new UserProfileDTO(fresh.getUsername(), fresh.getEmail(), fresh.getBio(), fresh.getBirthDate(), fresh.getLocation(), fresh.getWebsite(), fresh.getImageUrl(), fresh.getBannerUrl(), fresh.getCreatedAt(), fresh.getRole().name(), fresh.getSelectedBadge());
    }

    private Integer computeAge(LocalDate birthDate) {
        return birthDate != null ? Period.between(birthDate, LocalDate.now()).getYears() : null;
    }

    @Transactional
    public UserProfileDTO updateProfile(User principal, UpdateProfileRequestDTO request) {
        User user = userRepository.findById(principal.getId()).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        String previousImageUrl = user.getImageUrl();
        String previousBannerUrl = user.getBannerUrl();
        if (request.getBio() != null) {
            user.setBio(request.getBio().isBlank() ? null : request.getBio());
        }
        if (request.getBirthDate() != null && user.getBirthDate() == null) {
            minAgeValidator.validate(request.getBirthDate());
            user.setBirthDate(request.getBirthDate());
        }
        if (request.getLocation() != null) {
            user.setLocation(request.getLocation().isBlank() ? null : request.getLocation());
        }
        if (request.getWebsite() != null) {
            user.setWebsite(request.getWebsite().isBlank() ? null : request.getWebsite());
        }
        if (request.getImageUrl() != null) {
            String newImageUrl = request.getImageUrl().isBlank() ? null : request.getImageUrl();
            if (newImageUrl != null && !r2Client.isOwnedUrl(newImageUrl, "avatar", user.getId())) {
                throw new IllegalArgumentException("Invalid image URL");
            }
            user.setImageUrl(newImageUrl);
        }
        if (request.getBannerUrl() != null) {
            String newBannerUrl = request.getBannerUrl().isBlank() ? null : request.getBannerUrl();
            if (newBannerUrl != null) {
                if (!r2Client.isOwnedUrl(newBannerUrl, "banner", user.getId())) {
                    throw new IllegalArgumentException("Invalid banner URL");
                }
                if (!subscriptionService.isSupporter(user)) {
                    throw new LimitExceededException("Profile banners are a supporter perk. Upgrade to use one.");
                }
            }
            user.setBannerUrl(newBannerUrl);
        }
        if (request.getSelectedBadge() != null) {
            String badgeCode = request.getSelectedBadge().isBlank() ? null : request.getSelectedBadge();
            if (badgeCode != null) {
                boolean earned = userBadgeRepository.findByUserId(user.getId()).stream()
                        .anyMatch(ub -> ub.getBadgeCode().equals(badgeCode));
                if (!earned) {
                    throw new IllegalArgumentException("Badge not earned: " + badgeCode);
                }
            }
            user.setSelectedBadge(badgeCode);
        }
        User saved = userRepository.save(user);
        if (request.getImageUrl() != null && !request.getImageUrl().equals(previousImageUrl)) {
            r2Client.deleteByPublicUrl(previousImageUrl);
        }
        if (request.getBannerUrl() != null && !request.getBannerUrl().equals(previousBannerUrl)) {
            r2Client.deleteByPublicUrl(previousBannerUrl);
        }
        return new UserProfileDTO(saved.getUsername(), saved.getEmail(), saved.getBio(), saved.getBirthDate(), saved.getLocation(), saved.getWebsite(), saved.getImageUrl(), saved.getBannerUrl(), saved.getCreatedAt(), saved.getRole().name(), saved.getSelectedBadge());
    }

    private ProfileStatsDTO getProfileStats(User user) {
        long trailsPublished = projectRepository.countByOwnerIdAndVisibility(user.getId(), ProjectVisibility.PUBLISHED);
        long upvotesReceived = projectVoteRepository.countByProjectOwnerIdAndProjectPublished(user.getId());
        long totalViews = projectRepository.sumViewCountByOwnerIdAndPublished(user.getId());
        long forksCount = projectRepository.countByOwnerIdAndForkedFromNotNull(user.getId());
        long followersCount = followRepository.countByFollowedId(user.getId());
        long followingCount = followRepository.countByFollowerId(user.getId());
        return new ProfileStatsDTO(trailsPublished, upvotesReceived, totalViews, forksCount, followersCount, followingCount);
    }

    public ProfileStatsBundleDTO getProfileStatsBundle(User user) {
        ProfileStatsDTO stats = getProfileStats(user);
        List<BadgeDTO> badges = buildBadges(stats, subscriptionService.isSupporter(user));
        awardNewlyEarnedBadges(user, badges);
        return new ProfileStatsBundleDTO(stats, badges);
    }

    public PageResponseDTO<ProjectFeedItemDTO> getPublishedPage(User user, int page, int size) {
        Page<Project> result = projectRepository.findByOwnerIdAndVisibilityOrderByCreationDateDescPaged(
                user.getId(), ProjectVisibility.PUBLISHED, PageRequest.of(page, size));
        return new PageResponseDTO<>(toPublishedFeedItems(result.getContent(), user), result.hasNext());
    }

    public PageResponseDTO<ProjectFeedItemDTO> getBookmarksPage(User user, int page, int size) {
        Page<ProjectBookmark> result = projectBookmarkRepository.findByUserIdOrderByCreatedDateDescPaged(
                user.getId(), PageRequest.of(page, size));
        List<Project> projects = result.getContent().stream().map(ProjectBookmark::getProject).toList();
        return new PageResponseDTO<>(toFeedItems(projects, user), result.hasNext());
    }

    public PageResponseDTO<ProjectFeedItemDTO> getUpvotedPage(User user, int page, int size) {
        Page<ProjectVote> result = projectVoteRepository.findByUserIdOrderByCreatedDateDescPaged(
                user.getId(), PageRequest.of(page, size));
        List<Project> projects = result.getContent().stream().map(ProjectVote::getProject).toList();
        return new PageResponseDTO<>(toFeedItems(projects, user), result.hasNext());
    }

    public PageResponseDTO<ForkFeedItemDTO> getForksPage(User user, int page, int size) {
        Page<Project> result = projectRepository.findByOwnerIdAndForkedFromNotNullOrderByCreationDateDescPaged(
                user.getId(), PageRequest.of(page, size));
        return new PageResponseDTO<>(toForkFeedItems(result.getContent(), user), result.hasNext());
    }

    public PageResponseDTO<ActivityItemDTO> getActivityPage(User user, int page, int size) {
        List<ProjectBookmark> myBookmarks = projectBookmarkRepository.findByUserIdOrderByCreatedDateDesc(user.getId());
        List<ProjectVote> myVotes = projectVoteRepository.findByUserIdOrderByCreatedDateDesc(user.getId());
        List<Project> myForkedProjects = projectRepository.findByOwnerIdAndForkedFromNotNullOrderByCreationDateDesc(user.getId());
        List<Project> myPublishedProjects = projectRepository.findByOwnerIdAndVisibilityOrderByCreationDateDesc(user.getId(), ProjectVisibility.PUBLISHED);
        List<ActivityItemDTO> all = getMyActivity(user, myBookmarks, myVotes, myForkedProjects, myPublishedProjects);

        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return new PageResponseDTO<>(all.subList(from, to), to < all.size());
    }

    private void awardNewlyEarnedBadges(User user, List<BadgeDTO> badges) {
        Set<String> alreadyAwarded = userBadgeRepository.findByUserId(user.getId()).stream()
                .map(UserBadge::getBadgeCode)
                .collect(Collectors.toSet());

        for (BadgeDTO badge : badges) {
            if (badge.isEarned() && !alreadyAwarded.contains(badge.getCode())) {
                UserBadge userBadge = new UserBadge();
                userBadge.setUser(user);
                userBadge.setBadgeCode(badge.getCode());
                userBadge.setEarnedAt(new Date());
                userBadgeRepository.save(userBadge);
                notificationService.recordBadge(user, badge.getCode(), badge.getName());
            }
        }
    }

    private void checkAndAwardBadges(User user) {
        ProfileStatsDTO stats = getProfileStats(user);
        awardNewlyEarnedBadges(user, buildBadges(stats, subscriptionService.isSupporter(user)));
    }

    private boolean crossedViewBadgeThreshold(long before, long after) {
        return (before < ON_THE_MAP_VIEW_THRESHOLD && after >= ON_THE_MAP_VIEW_THRESHOLD)
                || (before < TRENDSETTER_VIEW_THRESHOLD && after >= TRENDSETTER_VIEW_THRESHOLD);
    }

    public PublicProfileDTO getPublicProfile(String username, User requester) {
        User target = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        ProfileStatsDTO stats = getProfileStats(target);
        boolean self = requester != null && requester.getId().equals(target.getId());
        boolean following = !self && requester != null
                && followRepository.findByFollowerIdAndFollowedId(requester.getId(), target.getId()).isPresent();
        boolean blocked = !self && requester != null
                && blockedUserRepository.findByBlockerIdAndBlockedId(requester.getId(), target.getId()).isPresent();

        Integer publicAge = Boolean.FALSE.equals(target.getShowAge()) ? null : computeAge(target.getBirthDate());
        return new PublicProfileDTO(target.getUsername(), target.getBio(), publicAge, target.getLocation(), target.getWebsite(), target.getImageUrl(), target.getBannerUrl(), target.getCreatedAt(),
                stats, buildBadges(stats, subscriptionService.isSupporter(target)), target.getSelectedBadge(), following, self, blocked);
    }

    // ponytail: findAll is fine at current scale, paginate if the published-project
    // count reaches the thousands.
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

    public PageResponseDTO<ProjectFeedItemDTO> getPublishedPageForUser(String username, User requester, int page, int size) {
        User target = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Page<Project> result = projectRepository.findByOwnerIdAndVisibilityOrderByCreationDateDescPaged(
                target.getId(), ProjectVisibility.PUBLISHED, PageRequest.of(page, size));
        return new PageResponseDTO<>(toPublishedFeedItems(result.getContent(), requester), result.hasNext());
    }

    @Transactional
    public FollowResponseDTO toggleFollow(String username, User requester) {
        User target = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (target.getId().equals(requester.getId())) {
            throw new AccessDeniedException("Cannot follow yourself");
        }
        if (blockedUserRepository.existsEitherDirection(requester.getId(), target.getId())) {
            throw new AccessDeniedException("Cannot follow this user");
        }

        var existing = followRepository.findByFollowerIdAndFollowedId(requester.getId(), target.getId());
        boolean following;
        if (existing.isPresent()) {
            followRepository.delete(existing.get());
            following = false;
        } else {
            Follow follow = new Follow();
            follow.setFollower(requester);
            follow.setFollowed(target);
            follow.setCreatedDate(new Date());
            followRepository.save(follow);
            following = true;
            notificationService.recordEvent(target, "FOLLOW", null, requester);
        }
        return new FollowResponseDTO(following, followRepository.countByFollowedId(target.getId()));
    }

    public PageResponseDTO<FollowUserDTO> getFollowers(String username, User requester, int page, int size) {
        User target = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Page<Follow> result = followRepository.findByFollowedIdOrderByCreatedDateDesc(target.getId(), PageRequest.of(page, size));
        List<User> users = result.getContent().stream().map(Follow::getFollower).toList();
        return toFollowUserPage(users, requester, result.hasNext());
    }

    public PageResponseDTO<FollowUserDTO> getFollowing(String username, User requester, int page, int size) {
        User target = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Page<Follow> result = followRepository.findByFollowerIdOrderByCreatedDateDesc(target.getId(), PageRequest.of(page, size));
        List<User> users = result.getContent().stream().map(Follow::getFollowed).toList();
        return toFollowUserPage(users, requester, result.hasNext());
    }

    private PageResponseDTO<FollowUserDTO> toFollowUserPage(List<User> users, User requester, boolean hasMore) {
        List<Long> ids = users.stream().map(User::getId).toList();
        Set<Long> followingIds = requester == null || ids.isEmpty()
                ? Set.of()
                : Set.copyOf(followRepository.findFollowedIdsIn(requester.getId(), ids));
        List<FollowUserDTO> items = users.stream()
                .map(u -> new FollowUserDTO(u.getUsername(), u.getImageUrl(), u.getBio(), followingIds.contains(u.getId())))
                .toList();
        return new PageResponseDTO<>(items, hasMore);
    }

    private List<BadgeDTO> buildBadges(ProfileStatsDTO stats, boolean supporter) {
        List<BadgeDTO> badges = new ArrayList<>();
        badges.add(badge(SubscriptionService.SUPPORTER_BADGE_CODE, "Supporter", "Support Tramo with a subscription", supporter ? 1 : 0, 1));
        badges.add(badge("first_publish", "First Publish", "Publish your first trail", stats.getTrailsPublished(), 1));
        badges.add(badge("prolific", "Prolific", "Publish 10 trails", stats.getTrailsPublished(), 10));
        badges.add(badge("rising_star", "Rising Star", "Earn 10 upvotes", stats.getUpvotesReceived(), 10));
        badges.add(badge("crowd_favorite", "Crowd Favorite", "Earn 100 upvotes", stats.getUpvotesReceived(), 100));
        badges.add(badge("on_the_map", "On the Map", "Reach 1,000 total views", stats.getTotalViews(), ON_THE_MAP_VIEW_THRESHOLD));
        badges.add(badge("trendsetter", "Trendsetter", "Reach 10,000 total views", stats.getTotalViews(), TRENDSETTER_VIEW_THRESHOLD));
        badges.add(badge("forked_once", "Forked Once", "Get forked by another user", stats.getForksCount(), 1));
        badges.add(badge("remix_king", "Remix King", "Get forked 25 times", stats.getForksCount(), 25));
        return badges;
    }

    private BadgeDTO badge(String code, String name, String description, long progress, long target) {
        return new BadgeDTO(code, name, description, progress >= target, Math.min(progress, target), target);
    }

    private List<ActivityItemDTO> getMyActivity(User user, List<ProjectBookmark> myBookmarks, List<ProjectVote> myVotes, List<Project> myForkedProjects, List<Project> myPublishedProjects) {
        Long userId = user.getId();
        List<ActivityItemDTO> items = new ArrayList<>();

        for (Project project : myPublishedProjects) {
            items.add(new ActivityItemDTO("published", project.getCreationDate(), projectIdCodec.encode(project.getId()), project.getTitle(), null));
        }
        for (Project project : myForkedProjects) {
            String sourceOwner = project.getForkedFrom() != null ? project.getForkedFrom().getOwner().getUsername() : null;
            items.add(new ActivityItemDTO("forked", project.getCreationDate(), projectIdCodec.encode(project.getId()), project.getTitle(), sourceOwner));
        }
        for (ProjectVote vote : myVotes) {
            items.add(new ActivityItemDTO("voted", vote.getCreatedDate(), projectIdCodec.encode(vote.getProject().getId()), vote.getProject().getTitle(), null));
        }
        for (ProjectBookmark bookmark : myBookmarks) {
            items.add(new ActivityItemDTO("bookmarked", bookmark.getCreatedDate(), projectIdCodec.encode(bookmark.getProject().getId()), bookmark.getProject().getTitle(), null));
        }
        for (ProjectVote vote : projectVoteRepository.findByProjectOwnerIdAndUserIdNotOrderByCreatedDateDesc(userId, userId)) {
            items.add(new ActivityItemDTO("received_vote", vote.getCreatedDate(), projectIdCodec.encode(vote.getProject().getId()), vote.getProject().getTitle(), vote.getUser().getUsername()));
        }
        for (Project project : projectRepository.findByForkedFromOwnerIdAndOwnerIdNotOrderByCreationDateDesc(userId, userId)) {
            Project source = project.getForkedFrom();
            items.add(new ActivityItemDTO("received_fork", project.getCreationDate(), projectIdCodec.encode(source.getId()), source.getTitle(), project.getOwner().getUsername()));
        }
        for (ProjectBookmark bookmark : projectBookmarkRepository.findByProjectOwnerIdAndUserIdNotOrderByCreatedDateDesc(userId, userId)) {
            items.add(new ActivityItemDTO("received_bookmark", bookmark.getCreatedDate(), projectIdCodec.encode(bookmark.getProject().getId()), bookmark.getProject().getTitle(), bookmark.getUser().getUsername()));
        }

        return items.stream()
                .sorted(Comparator.comparing(ActivityItemDTO::getTimestamp).reversed())
                .limit(50)
                .toList();
    }

    private List<ProjectFeedItemDTO> toFeedItems(List<Project> projects, User requester) {
        FeedContext ctx = FeedContext.forProjects(projects, requester, projectRepository, projectVoteRepository, projectBookmarkRepository, commentRepository)
                .withThumbnails(resolveThumbnails(projects));
        return projects.stream().map(project -> toFeedItem(project, ctx)).toList();
    }



    private List<ProjectFeedItemDTO> toPublishedFeedItems(List<Project> projects, User requester) {
        FeedContext ctx = FeedContext.forPublishedProjects(projects, requester, projectRepository, projectVoteRepository,
                projectBookmarkRepository, commentRepository, projectSnapshotRepository)
                .withThumbnails(resolveThumbnails(projects));
        return projects.stream().map(project -> toFeedItem(project, ctx)).toList();
    }

    private List<ForkFeedItemDTO> toForkFeedItems(List<Project> projects, User requester) {
        FeedContext ctx = FeedContext.forProjects(projects, requester, projectRepository, projectVoteRepository, projectBookmarkRepository, commentRepository)
                .withThumbnails(resolveThumbnails(projects));
        return projects.stream().map(project -> toForkFeedItem(project, requester.getUsername(), ctx)).toList();
    }

    private record FeedContext(Map<Long, Long> voteCounts, Map<Long, Long> forkCounts, Map<Long, Long> commentCounts,
                                Set<Long> votedProjectIds, Set<Long> bookmarkedProjectIds,
                                Map<Long, ProjectSnapshot> latestPublishByProjectId,
                                Map<Long, List<String>> tagNamesByProjectId,
                                Map<Long, ThumbnailResolution> thumbnailByProjectId) {
        FeedContext withThumbnails(Map<Long, ThumbnailResolution> thumbnails) {
            return new FeedContext(voteCounts, forkCounts, commentCounts, votedProjectIds, bookmarkedProjectIds,
                    latestPublishByProjectId, tagNamesByProjectId, thumbnails);
        }

        static FeedContext forProjects(
                List<Project> projects,
                User requester,
                ProjectRepository projectRepository,
                ProjectVoteRepository voteRepository,
                ProjectBookmarkRepository bookmarkRepository
        ) {
            List<Long> ids = projects.stream().map(Project::getId).toList();
            if (ids.isEmpty()) return new FeedContext(Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Map.of(), Map.of(), Map.of());

            Map<Long, Long> voteCounts = new HashMap<>();
            for (ProjectVoteRepository.ProjectVoteCount row : voteRepository.countGroupedByProjectIdIn(ids)) {
                voteCounts.put(row.getProjectId(), row.getVoteCount());
            }
            Map<Long, Long> forkCounts = new HashMap<>();
            for (ProjectRepository.ProjectForkCount row : projectRepository.countGroupedByForkedFromIdIn(ids)) {
                forkCounts.put(row.getProjectId(), row.getForkCount());
            }
            Set<Long> votedProjectIds = requester == null
                    ? Set.of()
                    : Set.copyOf(voteRepository.findVotedProjectIds(requester.getId(), ids));
            Set<Long> bookmarkedProjectIds = requester == null
                    ? Set.of()
                    : Set.copyOf(bookmarkRepository.findBookmarkedProjectIds(requester.getId(), ids));
            Map<Long, List<String>> tagNamesByProjectId = new HashMap<>();
            for (ProjectRepository.ProjectTagName row : projectRepository.findTagNamesGroupedByProjectIdIn(ids)) {
                tagNamesByProjectId.computeIfAbsent(row.getProjectId(), k -> new ArrayList<>()).add(row.getTagName());
            }
            return new FeedContext(voteCounts, forkCounts, Map.of(), votedProjectIds, bookmarkedProjectIds, Map.of(), tagNamesByProjectId, Map.of());
        }

        static FeedContext forProjects(
                List<Project> projects,
                User requester,
                ProjectRepository projectRepository,
                ProjectVoteRepository voteRepository,
                ProjectBookmarkRepository bookmarkRepository,
                CommentRepository commentRepository
        ) {
            FeedContext base = forProjects(projects, requester, projectRepository, voteRepository, bookmarkRepository);
            List<Long> ids = projects.stream().map(Project::getId).toList();
            if (ids.isEmpty()) return base;
            Map<Long, Long> commentCounts = new HashMap<>();
            for (CommentRepository.ProjectCommentCount row : commentRepository.countGroupedByProjectIdIn(ids)) {
                commentCounts.put(row.getProjectId(), row.getCommentCount());
            }
            return new FeedContext(base.voteCounts(), base.forkCounts(), commentCounts, base.votedProjectIds(), base.bookmarkedProjectIds(), Map.of(), base.tagNamesByProjectId(), Map.of());
        }

        
        
        static FeedContext forPublishedProjects(
                List<Project> projects,
                User requester,
                ProjectRepository projectRepository,
                ProjectVoteRepository voteRepository,
                ProjectBookmarkRepository bookmarkRepository,
                CommentRepository commentRepository,
                ProjectSnapshotRepository snapshotRepository
        ) {
            FeedContext base = forProjects(projects, requester, projectRepository, voteRepository, bookmarkRepository, commentRepository);
            List<Long> ids = projects.stream().map(Project::getId).toList();
            if (ids.isEmpty()) return base;
            Map<Long, ProjectSnapshot> latestPublish = new HashMap<>();
            for (ProjectSnapshot snapshot : snapshotRepository.findLatestPublishByProjectIdIn(ids)) {
                latestPublish.put(snapshot.getProject().getId(), snapshot);
            }
            return new FeedContext(base.voteCounts(), base.forkCounts(), base.commentCounts(), base.votedProjectIds(), base.bookmarkedProjectIds(), latestPublish, base.tagNamesByProjectId(), Map.of());
        }
    }

    private ProjectFeedItemDTO toFeedItem(Project project, FeedContext ctx) {
        ProjectSnapshot latestPublish = ctx.latestPublishByProjectId().get(project.getId());
        ProjectSnapshotData snapshot = latestPublish != null
                ? objectMapper.readValue(latestPublish.getContent(), ProjectSnapshotData.class) : null;
        Date lastPublishedDate = project.getLastPublishedDate() != null
                && !project.getLastPublishedDate().equals(project.getFirstPublishedDate())
                ? project.getLastPublishedDate() : null;
        ThumbnailResolution thumbnail = ctx.thumbnailByProjectId().getOrDefault(project.getId(), ThumbnailResolution.EMPTY);
        return new ProjectFeedItemDTO(
                projectIdCodec.encode(project.getId()),
                snapshot != null ? snapshot.title() : project.getTitle(),
                snapshot != null ? snapshot.description() : project.getDescription(),
                project.getOwner().getUsername(),
                project.getOwner().getImageUrl(),
                project.getOwner().getSelectedBadge(),
                thumbnail.imageUrl(),
                thumbnail.graph(),
                snapshot != null ? splitLegacySnapshotTags(snapshot.tags()) : ctx.tagNamesByProjectId().getOrDefault(project.getId(), List.of()),
                project.getModifiedDate(),
                project.getFirstPublishedDate(),
                lastPublishedDate,
                ctx.voteCounts().getOrDefault(project.getId(), 0L),
                ctx.votedProjectIds().contains(project.getId()),
                ctx.bookmarkedProjectIds().contains(project.getId()),
                project.getViewCount(),
                ctx.forkCounts().getOrDefault(project.getId(), 0L),
                ctx.commentCounts().getOrDefault(project.getId(), 0L),
                project.isFeatured()
        );
    }

    private ForkFeedItemDTO toForkFeedItem(Project project, String ownerUsername, FeedContext ctx) {
        Project source = project.getForkedFrom();
        ThumbnailResolution thumbnail = ctx.thumbnailByProjectId().getOrDefault(project.getId(), ThumbnailResolution.EMPTY);
        return new ForkFeedItemDTO(
                projectIdCodec.encode(project.getId()),
                project.getTitle(),
                project.getDescription(),
                ownerUsername,
                thumbnail.imageUrl(),
                thumbnail.graph(),
                ctx.tagNamesByProjectId().getOrDefault(project.getId(), List.of()),
                project.getModifiedDate(),
                ctx.voteCounts().getOrDefault(project.getId(), 0L),
                ctx.votedProjectIds().contains(project.getId()),
                ctx.bookmarkedProjectIds().contains(project.getId()),
                project.getViewCount(),
                ctx.forkCounts().getOrDefault(project.getId(), 0L),
                ctx.commentCounts().getOrDefault(project.getId(), 0L),
                project.isFeatured(),
                source != null ? projectIdCodec.encode(source.getId()) : null,
                source != null ? source.getTitle() : null,
                source != null ? source.getOwner().getUsername() : null
        );
    }

    private boolean matchesSearch(Project project, List<String> tagNames, String q) {
        return containsIgnoreCase(project.getTitle(), q)
                || containsIgnoreCase(project.getDescription(), q)
                || tagNames.stream().anyMatch(name -> containsIgnoreCase(name, q));
    }

    private boolean containsIgnoreCase(String value, String q) {
        return value != null && value.toLowerCase().contains(q);
    }

    public List<TagCountDTO> getHotTopics(int limit) {
        return tagService.hotTopics(limit).stream()
                .map(tag -> new TagCountDTO(tag.getName(), tag.getUsageCount()))
                .toList();
    }

    
    
    private List<String> splitLegacySnapshotTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }

    private List<String> normalizeTagNames(List<String> rawNames) {
        if (rawNames == null) return List.of();
        return rawNames.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
    }

    
    
    private List<String> liveTagNames(Project project) {
        return project.getProjectTags().stream().map(Tag::getName).sorted().toList();
    }

    
    private Map<Long, List<String>> tagNamesGroupedByProjectId(List<Long> projectIds) {
        Map<Long, List<String>> byProjectId = new HashMap<>();
        for (ProjectRepository.ProjectTagName row : projectRepository.findTagNamesGroupedByProjectIdIn(projectIds)) {
            byProjectId.computeIfAbsent(row.getProjectId(), k -> new ArrayList<>()).add(row.getTagName());
        }
        return byProjectId;
    }

    
    
    private List<String> resolveTagNames(Project project) {
        return tagNamesGroupedByProjectId(List.of(project.getId())).getOrDefault(project.getId(), List.of());
    }

    private PublicItemDTO toPublicItem(TrailItem trailItem, Map<Long, Item> projectItemById,
                                        Map<Long, List<Association>> outgoingByItemId) {
        Item item = trailItem.getItem();
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
                trailItem.getAnnotation(),
                trailItem.getAssociation() != null ? String.valueOf(trailItem.getAssociation().getId()) : null,
                associations);
    }

    public Project getOwnedProject(Long id, User requester) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        if (!project.getOwner().getId().equals(requester.getId())) {
            throw new AccessDeniedException("Not allowed to access this project");
        }
        return project;
    }

    
    
    
    
    private ProjectResponseDTO toResponse(Project project) {
        return toResponse(project, resolveTagNames(project));
    }

    private ProjectResponseDTO toResponse(Project project, List<String> tagNames) {
        long bytes = uploadRecordRepository.sumBytesByProjectId(project.getId())
                + itemRepository.sumContentBytesByProjectId(project.getId());
        return toResponse(project, bytes, tagNames);
    }

    private ProjectResponseDTO toResponse(Project project, long storageBytes, List<String> tagNames) {
        return toResponse(project, storageBytes, tagNames, resolveThumbnail(project));
    }

    private ProjectResponseDTO toResponse(Project project, long storageBytes, List<String> tagNames, ThumbnailResolution thumbnail) {
        return new ProjectResponseDTO(
                projectIdCodec.encode(project.getId()),
                project.getTitle(),
                project.getDescription(),
                project.getVisibility(),
                thumbnail.imageUrl(),
                thumbnail.graph(),
                tagNames,
                project.getCreationDate(),
                latestOf(project.getModifiedDate(), project.getLastEditedDate()),
                storageBytes
        );
    }

    // --- Thumbnail resolution -------------------------------------------------
    // Order: explicit choice (sticky, set only via publish flow) > first trail with
    // substantive associations (live graph) > first inserted image > nothing (placeholder).
    // Batched over a whole project list (feeds, "my projects") so query count doesn't
    // scale with list size or per-project trail count — see resolveThumbnails.

    private record ThumbnailResolution(String imageUrl, GraphPreviewDTO graph) {
        static final ThumbnailResolution EMPTY = new ThumbnailResolution(null, null);
    }

    private ThumbnailResolution resolveThumbnail(Project project) {
        return resolveThumbnails(List.of(project)).getOrDefault(project.getId(), ThumbnailResolution.EMPTY);
    }

    private Map<Long, ThumbnailResolution> resolveThumbnails(List<Project> projects) {
        Map<Long, ThumbnailResolution> result = new HashMap<>();
        List<Project> chosenGraphProjects = new ArrayList<>();
        List<Project> fallbackCandidates = new ArrayList<>();

        for (Project project : projects) {
            ProjectThumbnailType type = project.getThumbnailType();
            if (type == null || type == ProjectThumbnailType.NONE) {
                fallbackCandidates.add(project);
            } else if (type == ProjectThumbnailType.GRAPH) {
                if (project.getThumbnailTrail() != null) {
                    chosenGraphProjects.add(project);
                } else {
                    result.put(project.getId(), ThumbnailResolution.EMPTY);
                }
            } else {
                result.put(project.getId(), new ThumbnailResolution(project.getThumbnailImageUrl(), null));
            }
        }

        if (!chosenGraphProjects.isEmpty()) {
            List<Long> trailIds = chosenGraphProjects.stream().map(p -> p.getThumbnailTrail().getId()).toList();
            GraphLookup lookup = GraphLookup.forTrailIds(trailIds, trailItemRepository, itemLinkRepository);
            for (Project project : chosenGraphProjects) {
                Trail trail = project.getThumbnailTrail();
                result.put(project.getId(), new ThumbnailResolution(null, lookup.buildGraphPreview(trail)));
            }
        }

        if (!fallbackCandidates.isEmpty()) {
            List<Long> fallbackProjectIds = fallbackCandidates.stream().map(Project::getId).toList();
            Map<Long, List<Trail>> trailsByProjectId = trailRepository.findByProjectIdIn(fallbackProjectIds).stream()
                    .collect(Collectors.groupingBy(t -> t.getProject().getId(), LinkedHashMap::new, Collectors.toList()));
            List<Long> allTrailIds = trailsByProjectId.values().stream().flatMap(List::stream).map(Trail::getId).toList();
            GraphLookup lookup = GraphLookup.forTrailIds(allTrailIds, trailItemRepository, itemLinkRepository);

            List<Long> needsImageFallback = new ArrayList<>();
            for (Project project : fallbackCandidates) {
                GraphPreviewDTO chosen = null;
                for (Trail trail : trailsByProjectId.getOrDefault(project.getId(), List.of())) {
                    GraphPreviewDTO graph = lookup.buildGraphPreview(trail);
                    if (graph != null && graph.items().stream().anyMatch(i -> !i.associations().isEmpty())) {
                        chosen = graph;
                        break;
                    }
                }
                if (chosen != null) {
                    result.put(project.getId(), new ThumbnailResolution(null, chosen));
                } else {
                    needsImageFallback.add(project.getId());
                }
            }

            if (!needsImageFallback.isEmpty()) {
                Map<Long, ItemImageReference> firstImageByProjectId = new LinkedHashMap<>();
                for (ItemImageReference ref : itemImageReferenceRepository.findByProjectIdInOrderByItemIdAsc(needsImageFallback)) {
                    firstImageByProjectId.putIfAbsent(ref.getItem().getProject().getId(), ref);
                }
                for (Long projectId : needsImageFallback) {
                    ItemImageReference ref = firstImageByProjectId.get(projectId);
                    result.put(projectId, ref != null ? new ThumbnailResolution(ref.getUrl(), null) : ThumbnailResolution.EMPTY);
                }
            }
        }

        return result;
    }

    // Pre-fetched trail memberships + outgoing associations for a batch of trails, so
    // building each trail's graph preview is pure in-memory work (no per-trail queries).
    private record GraphLookup(Map<Long, List<TrailItem>> membershipsByTrailId, Map<Long, List<Association>> outgoingByItemId) {
        static GraphLookup forTrailIds(List<Long> trailIds, TrailItemRepository trailItemRepository,
                                        AssociationRepository itemLinkRepository) {
            if (trailIds.isEmpty()) return new GraphLookup(Map.of(), Map.of());
            Map<Long, List<TrailItem>> membershipsByTrailId = trailItemRepository
                    .findByTrailIdInWithItemContentAndAssociation(trailIds).stream()
                    .collect(Collectors.groupingBy(ti -> ti.getTrail().getId(), LinkedHashMap::new, Collectors.toList()));
            Set<Long> itemIds = membershipsByTrailId.values().stream().flatMap(List::stream)
                    .map(ti -> ti.getItem().getId()).collect(Collectors.toSet());
            Map<Long, List<Association>> outgoingByItemId = itemIds.isEmpty() ? Map.of()
                    : itemLinkRepository.findBySourceItemIdIn(itemIds).stream()
                            .collect(Collectors.groupingBy(a -> a.getSourceItem().getId()));
            return new GraphLookup(membershipsByTrailId, outgoingByItemId);
        }

        // ponytail: only includes this trail's own items (no cross-trail "loose" association
        // targets) — keeps this cheap for a thumbnail; the full editor graph view isn't bound by this.
        GraphPreviewDTO buildGraphPreview(Trail trail) {
            List<TrailItem> memberships = membershipsByTrailId.getOrDefault(trail.getId(), List.of());
            if (memberships.isEmpty()) return null;
            Map<Long, Item> itemById = memberships.stream()
                    .collect(Collectors.toMap(m -> m.getItem().getId(), TrailItem::getItem, (a, b) -> a, LinkedHashMap::new));
            List<String> itemIds = memberships.stream().map(m -> String.valueOf(m.getItem().getId())).toList();
            List<GraphPreviewDTO.GraphItemDTO> items = itemById.values().stream()
                    .map(item -> new GraphPreviewDTO.GraphItemDTO(
                            String.valueOf(item.getId()),
                            item.getTitle(),
                            outgoingByItemId.getOrDefault(item.getId(), List.of()).stream()
                                    .filter(a -> a.getTargetType() == AssociationTargetType.ITEM && itemById.containsKey(a.getTargetId()))
                                    .map(a -> new AssociationDTO(String.valueOf(a.getId()), a.getType().name(),
                                            a.getTargetType().name(), String.valueOf(a.getTargetId()),
                                            itemById.get(a.getTargetId()).getTitle()))
                                    .toList()
                    ))
                    .toList();
            return new GraphPreviewDTO(String.valueOf(trail.getId()), trail.getTitle(), itemIds, items);
        }
    }

    private Date latestOf(Date a, Date b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.after(b) ? a : b;
    }
}
