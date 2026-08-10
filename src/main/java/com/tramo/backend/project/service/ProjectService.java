package com.tramo.backend.project.service;

import com.tramo.backend.comment.repository.CommentRepository;
import com.tramo.backend.upload.ImageDeletionQueue;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.moderation.repository.CommentReportRepository;
import com.tramo.backend.moderation.repository.ProjectReportRepository;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.tag.service.TagService;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.AssociationTargetType;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.repository.AssociationRepository;
import com.tramo.backend.trail.repository.ItemImageReferenceRepository;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.project.dto.ProjectImageDTO;
import com.tramo.backend.project.dto.ProjectRequestDTO;
import com.tramo.backend.project.dto.ProjectResponseDTO;
import com.tramo.backend.project.dto.SetThumbnailRequestDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectThumbnailType;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.repository.ProjectBookmarkRepository;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectSnapshotRepository;
import com.tramo.backend.project.repository.ProjectViewRepository;
import com.tramo.backend.project.repository.ProjectVoteRepository;
import com.tramo.backend.user.entity.User;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProjectService {


    private final ProjectRepository projectRepository;
    private final TrailRepository trailRepository;
    private final TrailItemRepository trailItemRepository;
    private final ItemRepository itemRepository;
    private final ProjectVoteRepository projectVoteRepository;
    private final ProjectBookmarkRepository projectBookmarkRepository;
    private final ProjectViewRepository projectViewRepository;
    private final AssociationRepository itemLinkRepository;
    private final ItemImageReferenceRepository itemImageReferenceRepository;
    private final NotificationService notificationService;
    private final ProjectReportRepository projectReportRepository;
    private final CommentRepository commentRepository;
    private final CommentReportRepository commentReportRepository;
    private final UploadRecordRepository uploadRecordRepository;
    private final ProjectSnapshotRepository projectSnapshotRepository;
    private final TagService tagService;
    private final ImageDeletionQueue imageDeletionQueue;
    private final AccessGuard accessGuard;
    private final ProjectThumbnailResolver thumbnailResolver;
    private final ProjectResponseMapper responseMapper;
    private final ProjectPublishService publishService;

    public ProjectService(ProjectRepository projectRepository, TrailRepository trailRepository,
                           TrailItemRepository trailItemRepository, ItemRepository itemRepository,
                           ProjectVoteRepository projectVoteRepository, ProjectBookmarkRepository projectBookmarkRepository,
                           ProjectViewRepository projectViewRepository, AssociationRepository itemLinkRepository,
                           ItemImageReferenceRepository itemImageReferenceRepository, NotificationService notificationService,
                           ProjectReportRepository projectReportRepository, CommentRepository commentRepository,
                           CommentReportRepository commentReportRepository,
                           UploadRecordRepository uploadRecordRepository,
                           ProjectSnapshotRepository projectSnapshotRepository,
                           TagService tagService,
                           ImageDeletionQueue imageDeletionQueue,
                           AccessGuard accessGuard, ProjectThumbnailResolver thumbnailResolver,
                           ProjectResponseMapper responseMapper, ProjectPublishService publishService) {
        this.accessGuard = accessGuard;
        this.thumbnailResolver = thumbnailResolver;
        this.responseMapper = responseMapper;
        this.publishService = publishService;
        this.imageDeletionQueue = imageDeletionQueue;
        this.tagService = tagService;
        this.uploadRecordRepository = uploadRecordRepository;
        this.projectSnapshotRepository = projectSnapshotRepository;
        this.projectRepository = projectRepository;
        this.trailRepository = trailRepository;
        this.trailItemRepository = trailItemRepository;
        this.itemRepository = itemRepository;
        this.itemLinkRepository = itemLinkRepository;
        this.itemImageReferenceRepository = itemImageReferenceRepository;
        this.projectViewRepository = projectViewRepository;
        this.projectVoteRepository = projectVoteRepository;
        this.projectBookmarkRepository = projectBookmarkRepository;
        this.notificationService = notificationService;
        this.commentRepository = commentRepository;
        this.commentReportRepository = commentReportRepository;
        this.projectReportRepository = projectReportRepository;
    }

    @Transactional
    public ProjectResponseDTO create(ProjectRequestDTO request, User owner) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        if (request.getVisibility() == ProjectVisibility.PUBLISHED) {
            throw new IllegalArgumentException("Publish the project after creating it");
        }
        Project project = new Project();
        project.setTitle(request.getTitle());
        project.setDescription(request.getDescription());
        project.setVisibility(request.getVisibility());
        project.setOwner(owner);
        project.setCreationDate(new Date());
        project.setModifiedDate(new Date());
        tagService.applyProjectTags(project, responseMapper.normalizeTagNames(request.getTags()), owner.getId());
        Project saved = projectRepository.save(project);
        return responseMapper.toResponse(saved, responseMapper.liveTagNames(saved));
    }

    public List<ProjectResponseDTO> getAllForUser(User owner) {
        List<Project> projects = projectRepository.findByOwnerId(owner.getId());
        List<Long> projectIds = projects.stream().map(Project::getId).toList();
        Map<Long, Long> imageBytesByProjectId = uploadRecordRepository.sumBytesGroupedByProjectIdIn(projectIds).stream()
                .collect(Collectors.toMap(UploadRecordRepository.ProjectBytesSum::getProjectId, UploadRecordRepository.ProjectBytesSum::getBytes));
        Map<Long, Long> contentBytesByProjectId = itemRepository.sumContentBytesGroupedByProjectIdIn(projectIds).stream()
                .collect(Collectors.toMap(ItemRepository.ProjectContentBytesSum::getProjectId, ItemRepository.ProjectContentBytesSum::getBytes));
        Map<Long, List<String>> tagNamesByProjectId = responseMapper.tagNamesGroupedByProjectId(projectIds);
        Map<Long, ThumbnailResolution> thumbnailByProjectId = thumbnailResolver.resolveThumbnails(projects);
        return projects.stream()
                .map(p -> responseMapper.toResponse(p, imageBytesByProjectId.getOrDefault(p.getId(), 0L)
                        + contentBytesByProjectId.getOrDefault(p.getId(), 0L),
                        tagNamesByProjectId.getOrDefault(p.getId(), List.of()),
                        thumbnailByProjectId.getOrDefault(p.getId(), ThumbnailResolution.EMPTY)))
                .toList();
    }

    public ProjectResponseDTO getById(Long id, User requester) {
        return responseMapper.toResponse(accessGuard.getOwnedProject(id, requester));
    }

    @Transactional
    public ProjectResponseDTO update(Long id, ProjectRequestDTO request, User requester) {
        Project project = accessGuard.getOwnedProject(id, requester);
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
            tagService.applyProjectTags(project, responseMapper.normalizeTagNames(request.getTags()), requester.getId());
            touchesModifiedDate = true;
        }
        if (touchesModifiedDate) {
            project.setModifiedDate(new Date());
        }
        Project savedProject = projectRepository.save(project);
        ProjectResponseDTO response = responseMapper.toResponse(savedProject, responseMapper.liveTagNames(savedProject));
        if (request.getVisibility() == ProjectVisibility.PUBLISHED) {
            publishService.applyVisibilityTransition(project, previousVisibility, firstPublish, firstPublishTimestamp);
        }
        return response;
    }


    @Transactional
    public ProjectResponseDTO setThumbnail(Long id, SetThumbnailRequestDTO request, User requester) {
        Project project = accessGuard.getOwnedProject(id, requester);
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
        if (previousType == ProjectThumbnailType.DEDICATED && previousImageUrl != null
                && !previousImageUrl.equals(saved.getThumbnailImageUrl())) {
            imageDeletionQueue.queue(previousImageUrl, requester.getId());
        }
        return responseMapper.toResponse(saved, responseMapper.liveTagNames(saved));
    }

    public List<ProjectImageDTO> listProjectImages(Long id, User requester) {
        Project project = accessGuard.getOwnedProject(id, requester);
        return itemImageReferenceRepository.findByProjectIdOrderByItemIdAsc(project.getId()).stream()
                .map(ref -> new ProjectImageDTO(ref.getUrl(), String.valueOf(ref.getItem().getId()), ref.getItem().getTitle()))
                .toList();
    }



    @Transactional
    public void delete(Long id, User requester) {
        Project project = accessGuard.getOwnedProject(id, requester);
        tagService.applyProjectTags(project, List.of(), requester.getId());
        imageDeletionQueue.queue(project.getThumbnailImageUrl(), requester.getId());
        for (String url : itemImageReferenceRepository.findUrlsByProjectId(id)) {
            imageDeletionQueue.queue(url, requester.getId());
        }
        project.setThumbnailTrail(null);
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








    
    
    

















































    
    


    
    

    

    
    



    
    
    
    





}
