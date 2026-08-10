package com.tramo.backend.project.service;

import com.tramo.backend.comment.repository.CommentRepository;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.project.dto.ForkFeedItemDTO;
import com.tramo.backend.project.dto.ProjectFeedItemDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectSnapshot;
import com.tramo.backend.project.repository.ProjectBookmarkRepository;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectSnapshotRepository;
import com.tramo.backend.project.repository.ProjectVoteRepository;
import com.tramo.backend.project.snapshot.ProjectSnapshotData;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.service.PrivacyPolicy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ProjectFeedMapper {
    private final ProjectRepository projectRepository;
    private final ProjectVoteRepository projectVoteRepository;
    private final ProjectBookmarkRepository projectBookmarkRepository;
    private final CommentRepository commentRepository;
    private final ProjectSnapshotRepository projectSnapshotRepository;
    private final ProjectThumbnailResolver thumbnailResolver;
    private final ProjectIdCodec projectIdCodec;
    private final ObjectMapper objectMapper;
    private final PrivacyPolicy privacyPolicy;

    public ProjectFeedMapper(ProjectRepository projectRepository, ProjectVoteRepository projectVoteRepository,
                              ProjectBookmarkRepository projectBookmarkRepository, CommentRepository commentRepository,
                              ProjectSnapshotRepository projectSnapshotRepository,
                              ProjectThumbnailResolver thumbnailResolver, ProjectIdCodec projectIdCodec,
                              ObjectMapper objectMapper, PrivacyPolicy privacyPolicy) {
        this.projectRepository = projectRepository;
        this.projectVoteRepository = projectVoteRepository;
        this.projectBookmarkRepository = projectBookmarkRepository;
        this.commentRepository = commentRepository;
        this.projectSnapshotRepository = projectSnapshotRepository;
        this.thumbnailResolver = thumbnailResolver;
        this.projectIdCodec = projectIdCodec;
        this.objectMapper = objectMapper;
        this.privacyPolicy = privacyPolicy;
    }

    FeedContext contextFor(List<Project> projects, User requester) {
        return withComments(baseContext(projects, requester), projects)
                .withThumbnails(thumbnailResolver.resolveThumbnails(projects));
    }

    FeedContext publishedContextFor(List<Project> projects, User requester) {
        FeedContext base = withComments(baseContext(projects, requester), projects);
        List<Long> ids = projects.stream().map(Project::getId).toList();
        if (!ids.isEmpty()) {
            Map<Long, ProjectSnapshot> latestPublish = new HashMap<>();
            for (ProjectSnapshot snapshot : projectSnapshotRepository.findLatestPublishByProjectIdIn(ids)) {
                latestPublish.put(snapshot.getProject().getId(), snapshot);
            }
            base = base.withLatestPublish(latestPublish);
        }
        return base.withThumbnails(thumbnailResolver.resolveThumbnails(projects));
    }

    private FeedContext baseContext(List<Project> projects, User requester) {
        List<Long> ids = projects.stream().map(Project::getId).toList();
        if (ids.isEmpty()) return FeedContext.EMPTY;

        Map<Long, Long> voteCounts = new HashMap<>();
        for (ProjectVoteRepository.ProjectVoteCount row : projectVoteRepository.countGroupedByProjectIdIn(ids)) {
            voteCounts.put(row.getProjectId(), row.getVoteCount());
        }
        Map<Long, Long> forkCounts = new HashMap<>();
        for (ProjectRepository.ProjectForkCount row : projectRepository.countGroupedByForkedFromIdIn(ids)) {
            forkCounts.put(row.getProjectId(), row.getForkCount());
        }
        Set<Long> votedProjectIds = requester == null
                ? Set.of()
                : Set.copyOf(projectVoteRepository.findVotedProjectIds(requester.getId(), ids));
        Set<Long> bookmarkedProjectIds = requester == null
                ? Set.of()
                : Set.copyOf(projectBookmarkRepository.findBookmarkedProjectIds(requester.getId(), ids));
        Map<Long, List<String>> tagNamesByProjectId = new HashMap<>();
        for (ProjectRepository.ProjectTagName row : projectRepository.findTagNamesGroupedByProjectIdIn(ids)) {
            tagNamesByProjectId.computeIfAbsent(row.getProjectId(), k -> new ArrayList<>()).add(row.getTagName());
        }
        return new FeedContext(voteCounts, forkCounts, Map.of(), votedProjectIds, bookmarkedProjectIds, Map.of(),
                tagNamesByProjectId, Map.of());
    }

    private FeedContext withComments(FeedContext base, List<Project> projects) {
        List<Long> ids = projects.stream().map(Project::getId).toList();
        if (ids.isEmpty()) return base;
        Map<Long, Long> commentCounts = new HashMap<>();
        for (CommentRepository.ProjectCommentCount row : commentRepository.countGroupedByProjectIdIn(ids)) {
            commentCounts.put(row.getProjectId(), row.getCommentCount());
        }
        return base.withCommentCounts(commentCounts);
    }

    List<ProjectFeedItemDTO> toFeedItems(List<Project> allProjects, User requester) {
        Set<String> blockRelated = privacyPolicy.blockRelatedUsernames(requester);
        List<Project> projects = withoutBlockRelatedOwners(allProjects, blockRelated);
        FeedContext ctx = contextFor(projects, requester);
        return projects.stream().map(project -> toFeedItem(project, ctx, blockRelated)).toList();
    }

    List<ProjectFeedItemDTO> toPublishedFeedItems(List<Project> allProjects, User requester) {
        Set<String> blockRelated = privacyPolicy.blockRelatedUsernames(requester);
        List<Project> projects = withoutBlockRelatedOwners(allProjects, blockRelated);
        FeedContext ctx = publishedContextFor(projects, requester);
        return projects.stream().map(project -> toFeedItem(project, ctx, blockRelated)).toList();
    }

    List<ForkFeedItemDTO> toForkFeedItems(List<Project> projects, User requester) {
        FeedContext ctx = contextFor(projects, requester);
        return projects.stream().map(project -> toForkFeedItem(project, requester.getUsername(), ctx)).toList();
    }

    private List<Project> withoutBlockRelatedOwners(List<Project> projects, Set<String> blockRelated) {
        if (blockRelated.isEmpty()) {
            return projects;
        }
        return projects.stream()
                .filter(project -> !blockRelated.contains(project.getOwner().getUsername()))
                .toList();
    }

    ProjectFeedItemDTO toFeedItem(Project project, FeedContext ctx) {
        return toFeedItem(project, ctx, Set.of());
    }

    ProjectFeedItemDTO toFeedItem(Project project, FeedContext ctx, Set<String> blockRelated) {
        ProjectSnapshot latestPublish = ctx.latestPublishByProjectId().get(project.getId());
        ProjectSnapshotData snapshot = latestPublish != null
                ? objectMapper.readValue(latestPublish.getContent(), ProjectSnapshotData.class) : null;
        Date lastPublishedDate = project.getLastPublishedDate() != null
                && !project.getLastPublishedDate().equals(project.getFirstPublishedDate())
                ? project.getLastPublishedDate() : null;
        ThumbnailResolution thumbnail = ctx.thumbnailByProjectId().getOrDefault(project.getId(), ThumbnailResolution.EMPTY);
        Project source = project.getForkedFrom();
        if (source != null && blockRelated.contains(source.getOwner().getUsername())) {
            source = null;
        }
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
                project.isFeatured(),
                source != null ? projectIdCodec.encode(source.getId()) : null,
                source != null ? source.getTitle() : null,
                source != null ? source.getOwner().getUsername() : null,
                privacyPolicy.canFork(project.getOwner())
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

    private List<String> splitLegacySnapshotTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }
}
