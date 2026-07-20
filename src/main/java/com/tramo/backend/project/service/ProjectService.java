package com.tramo.backend.project.service;

import com.tramo.backend.comment.repository.CommentRepository;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.upload.R2Client;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.moderation.repository.CommentReportRepository;
import com.tramo.backend.moderation.repository.ProjectReportRepository;
import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.path.entity.Idea;
import com.tramo.backend.path.entity.IdeaContent;
import com.tramo.backend.path.entity.IdeaLink;
import com.tramo.backend.path.entity.Path;
import com.tramo.backend.path.entity.PathIdea;
import com.tramo.backend.path.repository.IdeaLinkRepository;
import com.tramo.backend.path.repository.IdeaRepository;
import com.tramo.backend.path.repository.PathIdeaRepository;
import com.tramo.backend.path.repository.PathRepository;
import com.tramo.backend.project.dto.ActivityItemDTO;
import com.tramo.backend.project.dto.AuthorCountDTO;
import com.tramo.backend.project.dto.BadgeDTO;
import com.tramo.backend.project.dto.BookmarkResponseDTO;
import com.tramo.backend.project.dto.ExploreBundleDTO;
import com.tramo.backend.project.dto.FollowResponseDTO;
import com.tramo.backend.project.dto.FollowUserDTO;
import com.tramo.backend.project.dto.ForkFeedItemDTO;
import com.tramo.backend.project.dto.PageResponseDTO;
import com.tramo.backend.project.dto.ProfileStatsBundleDTO;
import com.tramo.backend.project.dto.ProfileStatsDTO;
import com.tramo.backend.project.dto.ProjectFeedItemDTO;
import com.tramo.backend.project.dto.ProjectRequestDTO;
import com.tramo.backend.project.dto.ProjectResponseDTO;
import com.tramo.backend.project.dto.PublicIdeaDTO;
import com.tramo.backend.project.dto.PublicPathDTO;
import com.tramo.backend.project.dto.PublicProfileDTO;
import com.tramo.backend.project.dto.PublicProjectResponseDTO;
import com.tramo.backend.project.dto.TagCountDTO;
import com.tramo.backend.project.dto.UpdateProfileRequestDTO;
import com.tramo.backend.project.dto.UserProfileDTO;
import com.tramo.backend.project.dto.VoteResponseDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectBookmark;
import com.tramo.backend.project.entity.ProjectVote;
import com.tramo.backend.project.entity.ProjectView;
import com.tramo.backend.project.repository.ProjectBookmarkRepository;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectViewRepository;
import com.tramo.backend.project.repository.ProjectVoteRepository;
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
    private final PathRepository pathRepository;
    private final PathIdeaRepository pathIdeaRepository;
    private final IdeaRepository ideaRepository;
    private final ProjectVoteRepository projectVoteRepository;
    private final ProjectBookmarkRepository projectBookmarkRepository;
    private final ProjectViewRepository projectViewRepository;
    private final IdeaLinkRepository ideaLinkRepository;
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

    public ProjectService(ProjectRepository projectRepository, PathRepository pathRepository,
                           PathIdeaRepository pathIdeaRepository, IdeaRepository ideaRepository,
                           ProjectVoteRepository projectVoteRepository, ProjectBookmarkRepository projectBookmarkRepository,
                           ProjectViewRepository projectViewRepository, IdeaLinkRepository ideaLinkRepository,
                           UserRepository userRepository, FollowRepository followRepository,
                           BlockedUserRepository blockedUserRepository,
                           UserBadgeRepository userBadgeRepository, NotificationService notificationService,
                           ProjectReportRepository projectReportRepository, CommentRepository commentRepository,
                           CommentReportRepository commentReportRepository, ProjectIdCodec projectIdCodec,
                           R2Client r2Client, SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
        this.projectRepository = projectRepository;
        this.pathRepository = pathRepository;
        this.pathIdeaRepository = pathIdeaRepository;
        this.ideaRepository = ideaRepository;
        this.ideaLinkRepository = ideaLinkRepository;
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

    public ProjectResponseDTO create(ProjectRequestDTO request, User owner) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        Project project = new Project();
        project.setTitle(request.getTitle());
        project.setDescription(request.getDescription());
        project.setVisibility(request.getVisibility());
        project.setTags(normalizeTags(request.getTags()));
        project.setOwner(owner);
        project.setCreationDate(new Date());
        project.setModifiedDate(new Date());
        return toResponse(projectRepository.save(project));
    }

    public List<ProjectResponseDTO> getAllForUser(User owner) {
        return projectRepository.findByOwnerId(owner.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    public ProjectResponseDTO getById(Long id, User requester) {
        return toResponse(getOwnedProject(id, requester));
    }

    public ProjectResponseDTO update(Long id, ProjectRequestDTO request, User requester) {
        Project project = getOwnedProject(id, requester);
        String previousVisibility = project.getVisibility();
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
        if (request.getVisibility() != null) {
            if ("published".equals(request.getVisibility())
                    && (project.getDescription() == null || project.getDescription().isBlank())) {
                throw new IllegalArgumentException("Add a description before publishing");
            }
            project.setVisibility(request.getVisibility());
            if ("published".equals(request.getVisibility()) && project.getFirstPublishedDate() == null) {
                subscriptionService.assertCanPublish(requester);
                project.setFirstPublishedDate(new Date());
                firstPublish = true;
            }
            touchesModifiedDate = true;
        }
        String previousThumbnail = project.getThumbnail();
        if (request.getThumbnail() != null) {
            project.setThumbnail(request.getThumbnail());
        }
        if (request.getTags() != null) {
            project.setTags(normalizeTags(request.getTags()));
            touchesModifiedDate = true;
        }
        if (touchesModifiedDate) {
            project.setModifiedDate(new Date());
        }
        ProjectResponseDTO response = toResponse(projectRepository.save(project));
        if (request.getThumbnail() != null && !request.getThumbnail().equals(previousThumbnail)) {
            r2Client.deleteByPublicUrl(previousThumbnail);
        }
        if ("published".equals(request.getVisibility())) {
            checkAndAwardBadges(project.getOwner());
            // first-ever publish only — republishing after a temporary private is not news
            // (deliberate re-announcement is what SHARE is for)
            if (!"published".equals(previousVisibility) && firstPublish) {
                notifyFollowers(project.getOwner(), "PUBLISH", project);
            }
        }
        return response;
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
        for (Path path : pathRepository.findByProjectId(id)) {
            List<PathIdea> memberships = pathIdeaRepository.findByPathIdOrderByOrderIndexAsc(path.getId());
            for (PathIdea membership : memberships) {
                Long ideaId = membership.getIdea().getId();
                pathIdeaRepository.delete(membership);
                if (pathIdeaRepository.findByIdeaId(ideaId).isEmpty()) {
                    ideaLinkRepository.deleteBySourceIdeaIdOrTargetIdeaId(ideaId, ideaId);
                    ideaRepository.deleteById(ideaId);
                }
            }
            pathRepository.delete(path);
        }
        projectVoteRepository.deleteByProjectId(id);
        projectBookmarkRepository.deleteByProjectId(id);
        projectViewRepository.deleteByProjectId(id);
        notificationService.deleteAllForProject(id);
        projectReportRepository.deleteByProjectId(id);
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
        String visibility = project.getVisibility();
        if (!"unlisted".equals(visibility) && !"published".equals(visibility)) {
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
        fork.setVisibility("private");
        fork.setThumbnail(source.getThumbnail());
        fork.setTags(source.getTags());
        fork.setOwner(requester);
        fork.setForkedFrom(source);
        fork.setCreationDate(new Date());
        fork.setModifiedDate(new Date());
        fork = projectRepository.save(fork);

        Map<Long, Idea> ideaCopies = new HashMap<>();
        for (Path sourcePath : pathRepository.findByProjectId(sourceProjectId)) {
            Path pathCopy = new Path();
            pathCopy.setTitle(sourcePath.getTitle());
            pathCopy.setVisibility(sourcePath.getVisibility());
            pathCopy.setCreationDate(new Date());
            pathCopy.setModifiedDate(new Date());
            pathCopy.setProject(fork);
            pathCopy = pathRepository.save(pathCopy);

            for (PathIdea membership : pathIdeaRepository.findByPathIdOrderByOrderIndexAsc(sourcePath.getId())) {
                Idea ideaCopy = ideaCopies.computeIfAbsent(membership.getIdea().getId(),
                        ignored -> copyIdea(membership.getIdea()));

                PathIdea membershipCopy = new PathIdea();
                membershipCopy.setPath(pathCopy);
                membershipCopy.setIdea(ideaCopy);
                membershipCopy.setOrderIndex(membership.getOrderIndex());
                pathIdeaRepository.save(membershipCopy);
            }
        }

        Set<Long> copiedLinkIds = new HashSet<>();
        for (Long sourceIdeaId : ideaCopies.keySet()) {
            for (IdeaLink link : ideaLinkRepository.findBySourceIdeaIdOrTargetIdeaId(sourceIdeaId, sourceIdeaId)) {
                if (!copiedLinkIds.add(link.getId())) continue;
                Idea newSource = ideaCopies.get(link.getSourceIdea().getId());
                Idea newTarget = ideaCopies.get(link.getTargetIdea().getId());
                if (newSource == null || newTarget == null) continue;

                IdeaLink linkCopy = new IdeaLink();
                linkCopy.setSourceIdea(newSource);
                linkCopy.setTargetIdea(newTarget);
                linkCopy.setCreatedDate(new Date());
                ideaLinkRepository.save(linkCopy);
            }
        }

        notificationService.recordEvent(source.getOwner(), "FORK", source, requester);
        checkAndAwardBadges(source.getOwner());
        return toResponse(fork);
    }

    private Idea copyIdea(Idea source) {
        Idea copy = new Idea();
        copy.setTitle(source.getTitle());
        copy.setType(source.getType());
        copy.setCreatedDate(new Date());
        copy.setModifiedDate(new Date());
        if (source.getContent() != null) {
            IdeaContent contentCopy = new IdeaContent();
            contentCopy.setContent(source.getContent().getContent());
            contentCopy.setUpdatedDate(new Date());
            copy.setContent(contentCopy);
        }
        return ideaRepository.save(copy);
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

            if ("published".equals(project.getVisibility())) {
                long viewsAfter = projectRepository.sumViewCountByOwnerIdAndPublished(project.getOwner().getId());
                if (crossedViewBadgeThreshold(viewsAfter - 1, viewsAfter)) {
                    checkAndAwardBadges(project.getOwner());
                }
            }
        }

        List<Path> projectPaths = pathRepository.findByProjectId(id);
        Map<Long, List<PathIdea>> ideasByPathId = projectPaths.isEmpty()
                ? Map.of()
                : pathIdeaRepository.findByPathIdInWithIdeaAndContent(projectPaths.stream().map(Path::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(pathIdea -> pathIdea.getPath().getId()));

        List<PublicPathDTO> paths = projectPaths.stream()
                .map(path -> new PublicPathDTO(
                        path.getId(),
                        path.getTitle(),
                        ideasByPathId.getOrDefault(path.getId(), List.of()).stream()
                                .map(PathIdea::getIdea)
                                .map(this::toPublicIdea)
                                .toList()
                ))
                .toList();

        return new PublicProjectResponseDTO(
                projectIdCodec.encode(project.getId()),
                project.getTitle(),
                project.getDescription(),
                project.getOwner().getUsername(),
                project.getModifiedDate(),
                paths,
                projectVoteRepository.countByProjectId(id),
                requester != null && projectVoteRepository.findByProjectIdAndUserId(id, requester.getId()).isPresent(),
                requester != null && projectBookmarkRepository.findByProjectIdAndUserId(id, requester.getId()).isPresent(),
                viewCount,
                commentRepository.countGroupedByProjectIdIn(List.of(id)).stream()
                        .findFirst()
                        .map(CommentRepository.ProjectCommentCount::getCommentCount)
                        .orElse(0L)
        );
    }

    public List<ProjectFeedItemDTO> getPublishedFeed(String query, String sort, User requester) {
        String q = query == null ? "" : query.trim().toLowerCase();
        List<Project> published = projectRepository.findByVisibilityOrderByModifiedDateDesc("published").stream()
                .filter(project -> q.isEmpty() || matchesSearch(project, q))
                .toList();

        List<Long> publishedIds = published.stream().map(Project::getId).toList();
        Map<Long, Long> voteCounts = publishedIds.isEmpty()
                ? Map.of()
                : projectVoteRepository.countGroupedByProjectIdIn(publishedIds).stream()
                .collect(Collectors.toMap(ProjectVoteRepository.ProjectVoteCount::getProjectId, ProjectVoteRepository.ProjectVoteCount::getVoteCount));
        Map<Long, Long> forkCounts = publishedIds.isEmpty()
                ? Map.of()
                : projectRepository.countGroupedByForkedFromIdIn(publishedIds).stream()
                .collect(Collectors.toMap(ProjectRepository.ProjectForkCount::getProjectId, ProjectRepository.ProjectForkCount::getForkCount));
        Set<Long> votedProjectIds = requester == null || publishedIds.isEmpty()
                ? Set.of()
                : Set.copyOf(projectVoteRepository.findVotedProjectIds(requester.getId(), publishedIds));
        Set<Long> bookmarkedProjectIds = requester == null || publishedIds.isEmpty()
                ? Set.of()
                : Set.copyOf(projectBookmarkRepository.findBookmarkedProjectIds(requester.getId(), publishedIds));
        Map<Long, Long> commentCounts = publishedIds.isEmpty()
                ? Map.of()
                : commentRepository.countGroupedByProjectIdIn(publishedIds).stream()
                .collect(Collectors.toMap(CommentRepository.ProjectCommentCount::getProjectId, CommentRepository.ProjectCommentCount::getCommentCount));

        List<ProjectFeedItemDTO> feed = published.stream()
                .map(project -> new ProjectFeedItemDTO(
                        projectIdCodec.encode(project.getId()),
                        project.getTitle(),
                        project.getDescription(),
                        project.getOwner().getUsername(),
                        project.getOwner().getImageUrl(),
                        project.getThumbnail(),
                        project.getTags(),
                        project.getModifiedDate(),
                        voteCounts.getOrDefault(project.getId(), 0L),
                        votedProjectIds.contains(project.getId()),
                        bookmarkedProjectIds.contains(project.getId()),
                        project.getViewCount(),
                        forkCounts.getOrDefault(project.getId(), 0L),
                        commentCounts.getOrDefault(project.getId(), 0L),
                        project.isFeatured()
                ))
                .collect(Collectors.toList());

        if ("hot".equals(sort)) {
            feed.sort(Comparator.comparingLong(ProjectFeedItemDTO::getVoteCount).reversed()
                    .thenComparing(ProjectFeedItemDTO::getModifiedDate, Comparator.reverseOrder()));
        }
        return feed;
    }

    public ExploreBundleDTO getExploreBundle(String query, String sort, int page, int size, User requester) {
        String q = query == null ? "" : query.trim().toLowerCase();
        Pageable pageable = PageRequest.of(page, size);

        List<Project> pageProjects;
        boolean hasMore;
        if ("hot".equals(sort)) {
            Page<Long> idPage = projectRepository.findPublishedHotIds("published", q, pageable);
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
                Page<Project> projectPage = projectRepository.findPublishedRecentByOwners("published", followedIds, q, pageable);
                pageProjects = projectPage.getContent();
                hasMore = projectPage.hasNext();
            }
        } else {
            Page<Project> projectPage = projectRepository.findPublishedRecent("published", q, pageable);
            pageProjects = projectPage.getContent();
            hasMore = projectPage.hasNext();
        }

        FeedContext ctx = FeedContext.forProjects(pageProjects, requester, projectRepository, projectVoteRepository, projectBookmarkRepository, commentRepository);
        List<ProjectFeedItemDTO> feed = pageProjects.stream()
                .map(project -> toFeedItem(project, ctx))
                .toList();

        ProjectFeedItemDTO featured = null;
        List<TagCountDTO> hotTopics = List.of();
        List<AuthorCountDTO> activeAuthors = List.of();
        if (page == 0) {
            if (!"following".equals(sort)) {
                featured = projectRepository.findByFeaturedTrue()
                        .filter(project -> "published".equals(project.getVisibility()))
                        .map(project -> toFeedItem(project, FeedContext.forProjects(List.of(project), requester, projectRepository, projectVoteRepository, projectBookmarkRepository, commentRepository)))
                        .orElse(null);
            }
            hotTopics = cachedHotTopics;
            activeAuthors = cachedActiveAuthors;
        }

        return new ExploreBundleDTO(feed, hasMore, featured, hotTopics, activeAuthors);
    }

    public List<AuthorCountDTO> getActiveAuthors(int limit) {
        Map<String, Long> authorCounts = new LinkedHashMap<>();
        Map<String, String> authorAvatars = new HashMap<>();
        for (Project project : projectRepository.findByVisibilityOrderByModifiedDateDesc("published")) {
            authorCounts.merge(project.getOwner().getUsername(), 1L, Long::sum);
            authorAvatars.putIfAbsent(project.getOwner().getUsername(), project.getOwner().getImageUrl());
        }
        return authorCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> new AuthorCountDTO(entry.getKey(), authorAvatars.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void refreshFeaturedProject() {
        List<Project> published = projectRepository.findByVisibilityOrderByModifiedDateDesc("published");
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
        cachedHotTopics = getHotTopics(10);
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
        return new UserProfileDTO(user.getUsername(), user.getEmail(), user.getBio(), user.getImageUrl(), user.getCreatedAt(), user.getRole().name());
    }

    @Transactional
    public UserProfileDTO updateProfile(User user, UpdateProfileRequestDTO request) {
        String previousImageUrl = user.getImageUrl();
        if (request.getBio() != null) {
            user.setBio(request.getBio().isBlank() ? null : request.getBio());
        }
        if (request.getImageUrl() != null) {
            user.setImageUrl(request.getImageUrl().isBlank() ? null : request.getImageUrl());
        }
        User saved = userRepository.save(user);
        if (request.getImageUrl() != null && !request.getImageUrl().equals(previousImageUrl)) {
            r2Client.deleteByPublicUrl(previousImageUrl);
        }
        return new UserProfileDTO(saved.getUsername(), saved.getEmail(), saved.getBio(), saved.getImageUrl(), saved.getCreatedAt(), saved.getRole().name());
    }

    private ProfileStatsDTO getProfileStats(User user) {
        long pathsPublished = projectRepository.countByOwnerIdAndVisibility(user.getId(), "published");
        long upvotesReceived = projectVoteRepository.countByProjectOwnerIdAndProjectPublished(user.getId());
        long totalViews = projectRepository.sumViewCountByOwnerIdAndPublished(user.getId());
        long forksCount = projectRepository.countByOwnerIdAndForkedFromNotNull(user.getId());
        long followersCount = followRepository.countByFollowedId(user.getId());
        long followingCount = followRepository.countByFollowerId(user.getId());
        return new ProfileStatsDTO(pathsPublished, upvotesReceived, totalViews, forksCount, followersCount, followingCount);
    }

    public ProfileStatsBundleDTO getProfileStatsBundle(User user) {
        ProfileStatsDTO stats = getProfileStats(user);
        List<BadgeDTO> badges = buildBadges(stats, subscriptionService.isPremium(user));
        awardNewlyEarnedBadges(user, badges);
        return new ProfileStatsBundleDTO(stats, badges);
    }

    public PageResponseDTO<ProjectFeedItemDTO> getPublishedPage(User user, int page, int size) {
        Page<Project> result = projectRepository.findByOwnerIdAndVisibilityOrderByCreationDateDescPaged(
                user.getId(), "published", PageRequest.of(page, size));
        return new PageResponseDTO<>(toFeedItems(result.getContent(), user), result.hasNext());
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
        List<Project> myPublishedProjects = projectRepository.findByOwnerIdAndVisibilityOrderByCreationDateDesc(user.getId(), "published");
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
        awardNewlyEarnedBadges(user, buildBadges(stats, subscriptionService.isPremium(user)));
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

        return new PublicProfileDTO(target.getUsername(), target.getBio(), target.getImageUrl(), target.getCreatedAt(),
                stats, buildBadges(stats, subscriptionService.isPremium(target)), following, self, blocked);
    }

    public PageResponseDTO<ProjectFeedItemDTO> getPublishedPageForUser(String username, User requester, int page, int size) {
        User target = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Page<Project> result = projectRepository.findByOwnerIdAndVisibilityOrderByCreationDateDescPaged(
                target.getId(), "published", PageRequest.of(page, size));
        return new PageResponseDTO<>(toFeedItems(result.getContent(), requester), result.hasNext());
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
        badges.add(badge("first_publish", "First Publish", "Publish your first path", stats.getPathsPublished(), 1));
        badges.add(badge("prolific", "Prolific", "Publish 10 paths", stats.getPathsPublished(), 10));
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
        FeedContext ctx = FeedContext.forProjects(projects, requester, projectRepository, projectVoteRepository, projectBookmarkRepository, commentRepository);
        return projects.stream().map(project -> toFeedItem(project, ctx)).toList();
    }

    private List<ForkFeedItemDTO> toForkFeedItems(List<Project> projects, User requester) {
        FeedContext ctx = FeedContext.forProjects(projects, requester, projectRepository, projectVoteRepository, projectBookmarkRepository, commentRepository);
        return projects.stream().map(project -> toForkFeedItem(project, requester.getUsername(), ctx)).toList();
    }

    private record FeedContext(Map<Long, Long> voteCounts, Map<Long, Long> forkCounts, Map<Long, Long> commentCounts, Set<Long> votedProjectIds, Set<Long> bookmarkedProjectIds) {
        static FeedContext forProjects(
                List<Project> projects,
                User requester,
                ProjectRepository projectRepository,
                ProjectVoteRepository voteRepository,
                ProjectBookmarkRepository bookmarkRepository
        ) {
            List<Long> ids = projects.stream().map(Project::getId).toList();
            if (ids.isEmpty()) return new FeedContext(Map.of(), Map.of(), Map.of(), Set.of(), Set.of());

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
            return new FeedContext(voteCounts, forkCounts, Map.of(), votedProjectIds, bookmarkedProjectIds);
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
            return new FeedContext(base.voteCounts(), base.forkCounts(), commentCounts, base.votedProjectIds(), base.bookmarkedProjectIds());
        }
    }

    private ProjectFeedItemDTO toFeedItem(Project project, FeedContext ctx) {
        return new ProjectFeedItemDTO(
                projectIdCodec.encode(project.getId()),
                project.getTitle(),
                project.getDescription(),
                project.getOwner().getUsername(),
                project.getOwner().getImageUrl(),
                project.getThumbnail(),
                project.getTags(),
                project.getModifiedDate(),
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
        return new ForkFeedItemDTO(
                projectIdCodec.encode(project.getId()),
                project.getTitle(),
                project.getDescription(),
                ownerUsername,
                project.getThumbnail(),
                project.getTags(),
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

    private boolean matchesSearch(Project project, String q) {
        return containsIgnoreCase(project.getTitle(), q)
                || containsIgnoreCase(project.getDescription(), q)
                || containsIgnoreCase(project.getTags(), q);
    }

    private boolean containsIgnoreCase(String value, String q) {
        return value != null && value.toLowerCase().contains(q);
    }

    public List<TagCountDTO> getHotTopics(int limit) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Project project : projectRepository.findByVisibilityOrderByModifiedDateDesc("published")) {
            for (String tag : splitTags(project.getTags())) {
                counts.merge(tag, 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> new TagCountDTO(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }

    private String normalizeTags(String rawTags) {
        List<String> tags = splitTags(rawTags == null ? "" : rawTags.toLowerCase())
                .stream()
                .distinct()
                .toList();
        return String.join(",", tags);
    }

    private PublicIdeaDTO toPublicIdea(Idea idea) {
        String content = idea.getContent() != null ? idea.getContent().getContent() : "";
        return new PublicIdeaDTO(idea.getId(), idea.getTitle(), idea.getType(), content, idea.getTitleAlign());
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
        return new ProjectResponseDTO(
                projectIdCodec.encode(project.getId()),
                project.getTitle(),
                project.getDescription(),
                project.getVisibility(),
                project.getThumbnail(),
                project.getTags(),
                project.getCreationDate(),
                latestOf(project.getModifiedDate(), project.getLastEditedDate())
        );
    }

    private Date latestOf(Date a, Date b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.after(b) ? a : b;
    }
}
