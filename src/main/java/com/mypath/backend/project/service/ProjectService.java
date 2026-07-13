package com.mypath.backend.project.service;

import com.mypath.backend.exception.ResourceNotFoundException;
import com.mypath.backend.notification.service.NotificationService;
import com.mypath.backend.path.entity.Idea;
import com.mypath.backend.path.entity.IdeaContent;
import com.mypath.backend.path.entity.IdeaLink;
import com.mypath.backend.path.entity.Path;
import com.mypath.backend.path.entity.PathIdea;
import com.mypath.backend.path.repository.IdeaLinkRepository;
import com.mypath.backend.path.repository.IdeaRepository;
import com.mypath.backend.path.repository.PathIdeaRepository;
import com.mypath.backend.path.repository.PathRepository;
import com.mypath.backend.project.dto.ActivityItemDTO;
import com.mypath.backend.project.dto.BadgeDTO;
import com.mypath.backend.project.dto.BookmarkResponseDTO;
import com.mypath.backend.project.dto.FollowResponseDTO;
import com.mypath.backend.project.dto.ForkFeedItemDTO;
import com.mypath.backend.project.dto.ProfileBundleDTO;
import com.mypath.backend.project.dto.ProfileStatsDTO;
import com.mypath.backend.project.dto.ProjectFeedItemDTO;
import com.mypath.backend.project.dto.ProjectRequestDTO;
import com.mypath.backend.project.dto.ProjectResponseDTO;
import com.mypath.backend.project.dto.PublicIdeaDTO;
import com.mypath.backend.project.dto.PublicPathDTO;
import com.mypath.backend.project.dto.PublicProfileDTO;
import com.mypath.backend.project.dto.PublicProjectResponseDTO;
import com.mypath.backend.project.dto.TagCountDTO;
import com.mypath.backend.project.dto.UpdateProfileRequestDTO;
import com.mypath.backend.project.dto.UserProfileDTO;
import com.mypath.backend.project.dto.VoteResponseDTO;
import com.mypath.backend.project.entity.Project;
import com.mypath.backend.project.entity.ProjectBookmark;
import com.mypath.backend.project.entity.ProjectVote;
import com.mypath.backend.project.entity.ProjectView;
import com.mypath.backend.project.repository.ProjectBookmarkRepository;
import com.mypath.backend.project.repository.ProjectRepository;
import com.mypath.backend.project.repository.ProjectViewRepository;
import com.mypath.backend.project.repository.ProjectVoteRepository;
import com.mypath.backend.user.entity.Follow;
import com.mypath.backend.user.entity.User;
import com.mypath.backend.user.entity.UserBadge;
import com.mypath.backend.user.repository.FollowRepository;
import com.mypath.backend.user.repository.UserBadgeRepository;
import com.mypath.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
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
    private final UserBadgeRepository userBadgeRepository;
    private final NotificationService notificationService;

    public ProjectService(ProjectRepository projectRepository, PathRepository pathRepository,
                           PathIdeaRepository pathIdeaRepository, IdeaRepository ideaRepository,
                           ProjectVoteRepository projectVoteRepository, ProjectBookmarkRepository projectBookmarkRepository,
                           ProjectViewRepository projectViewRepository, IdeaLinkRepository ideaLinkRepository,
                           UserRepository userRepository, FollowRepository followRepository,
                           UserBadgeRepository userBadgeRepository, NotificationService notificationService) {
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
        this.userBadgeRepository = userBadgeRepository;
        this.notificationService = notificationService;
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
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            project.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            project.setDescription(request.getDescription());
        }
        if (request.getVisibility() != null) {
            project.setVisibility(request.getVisibility());
        }
        if (request.getThumbnail() != null) {
            project.setThumbnail(request.getThumbnail());
        }
        if (request.getTags() != null) {
            project.setTags(normalizeTags(request.getTags()));
        }
        project.setModifiedDate(new Date());
        ProjectResponseDTO response = toResponse(projectRepository.save(project));
        if ("published".equals(request.getVisibility())) {
            checkAndAwardBadges(project.getOwner());
        }
        return response;
    }

    @Transactional
    public void delete(Long id, User requester) {
        Project project = getOwnedProject(id, requester);
        // Paths/ideas don't cascade from Project, so they need the same manual
        // cleanup PathService.delete does for a single path — otherwise the FK
        // from path -> project blocks the delete below.
        for (Path path : pathRepository.findByProjectId(id)) {
            List<PathIdea> memberships = pathIdeaRepository.findByPathIdOrderByOrderIndexAsc(path.getId());
            for (PathIdea membership : memberships) {
                Long ideaId = membership.getIdea().getId();
                pathIdeaRepository.delete(membership);
                if (pathIdeaRepository.findByIdeaId(ideaId).isEmpty()) {
                    ideaRepository.deleteById(ideaId);
                }
            }
            pathRepository.delete(path);
        }
        projectVoteRepository.deleteByProjectId(id);
        projectBookmarkRepository.deleteByProjectId(id);
        projectViewRepository.deleteByProjectId(id);
        // Forks keep their own copied content, so they just lose the "forked from"
        // attribution rather than being blocked or cascade-deleted.
        projectRepository.clearForkedFromReferences(id);
        projectRepository.delete(project);
    }

    // Both "unlisted" (share-link only) and "published" (also in the public feed)
    // are viewable here — only "private" (or unset, the default) is blocked.
    // 404s the same as a nonexistent project either way, so this can't be used
    // to probe which project IDs exist and are simply private.
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

        // Ideas can be shared across multiple paths within the same project
        // (PathIdea join), so copies are keyed by source idea id to preserve
        // that sharing rather than duplicating an idea once per path.
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

        // Idea links are looked up from both sides, so track which link ids have
        // already been copied to avoid creating each one twice.
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

        // Deduplicated per viewer, not per page load: logged-in visitors key by
        // user id, anonymous ones by the "anon-id" cookie middleware.ts mints on
        // first visit to a /p/* page. No stable identity (e.g. a bare API call
        // with no cookie) means the view just isn't counted, rather than falling
        // back to counting every request.
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
            // The in-memory `project` entity is stale after this write, so the
            // response below uses the locally tracked count rather than a second read.
            projectRepository.incrementViewCount(id);
            viewCount++;
        }

        List<PublicPathDTO> paths = pathRepository.findByProjectId(id).stream()
                .map(path -> new PublicPathDTO(
                        path.getId(),
                        path.getTitle(),
                        pathIdeaRepository.findByPathIdOrderByOrderIndexAsc(path.getId()).stream()
                                .map(pathIdea -> pathIdea.getIdea())
                                .map(this::toPublicIdea)
                                .toList()
                ))
                .toList();

        return new PublicProjectResponseDTO(
                project.getId(),
                project.getTitle(),
                project.getDescription(),
                project.getOwner().getUsername(),
                project.getModifiedDate(),
                paths,
                projectVoteRepository.countByProjectId(id),
                requester != null && projectVoteRepository.findByProjectIdAndUserId(id, requester.getId()).isPresent(),
                requester != null && projectBookmarkRepository.findByProjectIdAndUserId(id, requester.getId()).isPresent(),
                viewCount
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
        Set<Long> votedProjectIds = requester == null
                ? Set.of()
                : projectVoteRepository.findByUserIdAndProjectIdIn(requester.getId(), publishedIds)
                .stream().map(vote -> vote.getProject().getId()).collect(Collectors.toSet());
        Set<Long> bookmarkedProjectIds = requester == null
                ? Set.of()
                : projectBookmarkRepository.findByUserIdAndProjectIdIn(requester.getId(), publishedIds)
                .stream().map(bookmark -> bookmark.getProject().getId()).collect(Collectors.toSet());

        List<ProjectFeedItemDTO> feed = published.stream()
                .map(project -> new ProjectFeedItemDTO(
                        project.getId(),
                        project.getTitle(),
                        project.getDescription(),
                        project.getOwner().getUsername(),
                        project.getThumbnail(),
                        project.getTags(),
                        project.getModifiedDate(),
                        voteCounts.getOrDefault(project.getId(), 0L),
                        votedProjectIds.contains(project.getId()),
                        bookmarkedProjectIds.contains(project.getId()),
                        project.getViewCount(),
                        forkCounts.getOrDefault(project.getId(), 0L),
                        project.isFeatured()
                ))
                .collect(Collectors.toList());

        if ("hot".equals(sort)) {
            feed.sort(Comparator.comparingLong(ProjectFeedItemDTO::getVoteCount).reversed()
                    .thenComparing(ProjectFeedItemDTO::getModifiedDate, Comparator.reverseOrder()));
        }
        return feed;
    }

    // Runs once a day rather than being derived live, so "featured" is a
    // stable fact (see the comment on Project.featured) — only writes/
    // notifies when the top project actually changed since last run, so it
    // can't flap within a day even if vote counts tie or shuffle.
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void refreshFeaturedProject() {
        List<Project> published = projectRepository.findByVisibilityOrderByModifiedDateDesc("published");
        if (published.isEmpty()) return;

        List<Long> ids = published.stream().map(Project::getId).toList();
        Map<Long, Long> voteCounts = projectVoteRepository.countGroupedByProjectIdIn(ids).stream()
                .collect(Collectors.toMap(ProjectVoteRepository.ProjectVoteCount::getProjectId, ProjectVoteRepository.ProjectVoteCount::getVoteCount));

        Project top = published.stream()
                .max(Comparator.comparingLong(p -> voteCounts.getOrDefault(p.getId(), 0L)))
                .orElseThrow();

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

    @Transactional
    public VoteResponseDTO toggleVote(Long projectId, User requester) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        var existingVote = projectVoteRepository.findByProjectIdAndUserId(projectId, requester.getId());
        boolean voted;
        if (existingVote.isPresent()) {
            projectVoteRepository.delete(existingVote.get());
            voted = false;
        } else {
            ProjectVote vote = new ProjectVote();
            vote.setProject(project);
            vote.setUser(requester);
            vote.setCreatedDate(new Date());
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
        return new UserProfileDTO(user.getUsername(), user.getBio(), user.getImageUrl(), user.getCreatedAt());
    }

    @Transactional
    public UserProfileDTO updateProfile(User user, UpdateProfileRequestDTO request) {
        if (request.getBio() != null) {
            user.setBio(request.getBio().isBlank() ? null : request.getBio());
        }
        if (request.getImageUrl() != null) {
            user.setImageUrl(request.getImageUrl().isBlank() ? null : request.getImageUrl());
        }
        User saved = userRepository.save(user);
        return new UserProfileDTO(saved.getUsername(), saved.getBio(), saved.getImageUrl(), saved.getCreatedAt());
    }

    private ProfileStatsDTO getProfileStats(User user) {
        long pathsPublished = projectRepository.countByOwnerIdAndVisibility(user.getId(), "published");
        long upvotesReceived = projectVoteRepository.countByProjectOwnerIdAndProjectPublished(user.getId());
        long totalViews = projectRepository.sumViewCountByOwnerIdAndPublished(user.getId());
        long forksCount = projectRepository.countByOwnerIdAndForkedFromNotNull(user.getId());
        long followersCount = followRepository.countByFollowedId(user.getId());
        return new ProfileStatsDTO(pathsPublished, upvotesReceived, totalViews, forksCount, followersCount);
    }

    // One call assembling everything the profile page needs, instead of 7
    // separate REST round trips each paying their own JWT-auth user lookup —
    // and badges reuse this single stats computation instead of recomputing it.
    // Bookmarks/votes/forks are each fetched exactly once here and shared
    // between their own tab and the activity feed, which needs the same rows
    // (previously they were separate REST calls with no way to share a query
    // result, so each list was queried twice per page load).
    public ProfileBundleDTO getProfileBundle(User user) {
        ProfileStatsDTO stats = getProfileStats(user);
        List<BadgeDTO> badges = buildBadges(stats);
        awardNewlyEarnedBadges(user, badges);

        List<ProjectBookmark> myBookmarks = projectBookmarkRepository.findByUserIdOrderByCreatedDateDesc(user.getId());
        List<ProjectVote> myVotes = projectVoteRepository.findByUserIdOrderByCreatedDateDesc(user.getId());
        List<Project> myForkedProjects = projectRepository.findByOwnerIdAndForkedFromNotNullOrderByCreationDateDesc(user.getId());
        List<Project> myPublishedProjects = projectRepository.findByOwnerIdAndVisibilityOrderByCreationDateDesc(user.getId(), "published");

        List<ProjectFeedItemDTO> bookmarks = toFeedItems(myBookmarks.stream().map(ProjectBookmark::getProject).toList(), user);
        List<ProjectFeedItemDTO> upvoted = toFeedItems(myVotes.stream().map(ProjectVote::getProject).toList(), user);
        List<ForkFeedItemDTO> forks = toForkFeedItems(myForkedProjects, user);
        List<ProjectFeedItemDTO> published = toFeedItems(myPublishedProjects, user);
        List<ActivityItemDTO> activity = getMyActivity(user, myBookmarks, myVotes, myForkedProjects, myPublishedProjects);

        return new ProfileBundleDTO(stats, badges, bookmarks, upvoted, forks, published, activity);
    }

    // Badges are computed live every request (see buildBadges) with no
    // persisted "earned" state, so this is where the not-earned -> earned
    // transition actually gets detected and turned into a one-time award +
    // notification — checked whenever the owner loads their own bundle,
    // rather than threading a check into every stat-changing action.
    // Not @Transactional: Spring's proxy-based AOP can't intercept a private
    // method called via self-invocation anyway, so it'd be a no-op here — the
    // save() and notificationService.recordBadge() calls below each already
    // get correct transactional semantics from their own bean proxies.
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

    // Called right after the specific action that could cross a threshold
    // (vote received, fork received, publish) instead of only lazily on the
    // owner's next /profile visit — otherwise a badge earned mid-session
    // wouldn't notify until they happened to load their own profile.
    private void checkAndAwardBadges(User user) {
        ProfileStatsDTO stats = getProfileStats(user);
        awardNewlyEarnedBadges(user, buildBadges(stats));
    }

    // requester is null for an anonymous visitor — public, no auth required.
    // Deliberately narrower than the owner's own bundle: no bookmarks/upvoted/
    // forks/activity, since those reveal another person's browsing behavior.
    public PublicProfileDTO getPublicProfile(String username, User requester) {
        User target = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        ProfileStatsDTO stats = getProfileStats(target);
        List<ProjectFeedItemDTO> published = toFeedItems(
                projectRepository.findByOwnerIdAndVisibilityOrderByCreationDateDesc(target.getId(), "published"),
                requester
        );
        boolean self = requester != null && requester.getId().equals(target.getId());
        boolean following = !self && requester != null
                && followRepository.findByFollowerIdAndFollowedId(requester.getId(), target.getId()).isPresent();

        return new PublicProfileDTO(target.getUsername(), target.getBio(), target.getImageUrl(), target.getCreatedAt(),
                stats, buildBadges(stats), published, following, self);
    }

    @Transactional
    public FollowResponseDTO toggleFollow(String username, User requester) {
        User target = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (target.getId().equals(requester.getId())) {
            throw new AccessDeniedException("Cannot follow yourself");
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

    // Badges are computed live off the same stats as getProfileStats rather than
    // persisted/earned-once, so raising a stat (or a project getting unpublished)
    // always reflects the current true state instead of a stale snapshot.
    private List<BadgeDTO> buildBadges(ProfileStatsDTO stats) {
        List<BadgeDTO> badges = new ArrayList<>();
        badges.add(badge("first_publish", "First Publish", "Publish your first path", stats.getPathsPublished(), 1));
        badges.add(badge("prolific", "Prolific", "Publish 10 paths", stats.getPathsPublished(), 10));
        badges.add(badge("rising_star", "Rising Star", "Earn 10 upvotes", stats.getUpvotesReceived(), 10));
        badges.add(badge("crowd_favorite", "Crowd Favorite", "Earn 100 upvotes", stats.getUpvotesReceived(), 100));
        badges.add(badge("on_the_map", "On the Map", "Reach 1,000 total views", stats.getTotalViews(), 1000));
        badges.add(badge("trendsetter", "Trendsetter", "Reach 10,000 total views", stats.getTotalViews(), 10000));
        badges.add(badge("forked_once", "Forked Once", "Get forked by another user", stats.getForksCount(), 1));
        badges.add(badge("remix_king", "Remix King", "Get forked 25 times", stats.getForksCount(), 25));
        return badges;
    }

    private BadgeDTO badge(String code, String name, String description, long progress, long target) {
        return new BadgeDTO(code, name, description, progress >= target, Math.min(progress, target), target);
    }

    // Merges across five existing tables (votes/bookmarks/forks in both
    // directions, plus publishes) at read time instead of a dedicated event
    // log table, since every source already carries the timestamp/FKs needed.
    // Bookmarks/votes/forks are passed in already-loaded (getProfileBundle
    // fetched them for the tabs) rather than re-queried here.
    private List<ActivityItemDTO> getMyActivity(User user, List<ProjectBookmark> myBookmarks, List<ProjectVote> myVotes, List<Project> myForkedProjects, List<Project> myPublishedProjects) {
        Long userId = user.getId();
        List<ActivityItemDTO> items = new ArrayList<>();

        for (Project project : myPublishedProjects) {
            items.add(new ActivityItemDTO("published", project.getCreationDate(), project.getId(), project.getTitle(), null));
        }
        for (Project project : myForkedProjects) {
            String sourceOwner = project.getForkedFrom() != null ? project.getForkedFrom().getOwner().getUsername() : null;
            items.add(new ActivityItemDTO("forked", project.getCreationDate(), project.getId(), project.getTitle(), sourceOwner));
        }
        for (ProjectVote vote : myVotes) {
            items.add(new ActivityItemDTO("voted", vote.getCreatedDate(), vote.getProject().getId(), vote.getProject().getTitle(), null));
        }
        for (ProjectBookmark bookmark : myBookmarks) {
            items.add(new ActivityItemDTO("bookmarked", bookmark.getCreatedDate(), bookmark.getProject().getId(), bookmark.getProject().getTitle(), null));
        }
        for (ProjectVote vote : projectVoteRepository.findByProjectOwnerIdAndUserIdNotOrderByCreatedDateDesc(userId, userId)) {
            items.add(new ActivityItemDTO("received_vote", vote.getCreatedDate(), vote.getProject().getId(), vote.getProject().getTitle(), vote.getUser().getUsername()));
        }
        for (Project project : projectRepository.findByForkedFromOwnerIdAndOwnerIdNotOrderByCreationDateDesc(userId, userId)) {
            Project source = project.getForkedFrom();
            items.add(new ActivityItemDTO("received_fork", project.getCreationDate(), source.getId(), source.getTitle(), project.getOwner().getUsername()));
        }
        for (ProjectBookmark bookmark : projectBookmarkRepository.findByProjectOwnerIdAndUserIdNotOrderByCreatedDateDesc(userId, userId)) {
            items.add(new ActivityItemDTO("received_bookmark", bookmark.getCreatedDate(), bookmark.getProject().getId(), bookmark.getProject().getTitle(), bookmark.getUser().getUsername()));
        }

        return items.stream()
                .sorted(Comparator.comparing(ActivityItemDTO::getTimestamp).reversed())
                .limit(50)
                .toList();
    }

    private List<ProjectFeedItemDTO> toFeedItems(List<Project> projects, User requester) {
        FeedContext ctx = FeedContext.forProjects(projects, requester, projectRepository, projectVoteRepository, projectBookmarkRepository);
        return projects.stream().map(project -> toFeedItem(project, ctx)).toList();
    }

    private List<ForkFeedItemDTO> toForkFeedItems(List<Project> projects, User requester) {
        FeedContext ctx = FeedContext.forProjects(projects, requester, projectRepository, projectVoteRepository, projectBookmarkRepository);
        // These are always the caller's own projects, so the owner username is
        // already known — no need to touch project.getOwner() at all here,
        // which is what let the repository query above skip fetching it.
        return projects.stream().map(project -> toForkFeedItem(project, requester.getUsername(), ctx)).toList();
    }

    // Vote/fork counts + the requester's own vote/bookmark status for a batch
    // of projects, fetched in four grouped queries total instead of per-row
    // (what made a 20-item feed fire 60+ extra queries).
    private record FeedContext(Map<Long, Long> voteCounts, Map<Long, Long> forkCounts, Set<Long> votedProjectIds, Set<Long> bookmarkedProjectIds) {
        static FeedContext forProjects(
                List<Project> projects,
                User requester,
                ProjectRepository projectRepository,
                ProjectVoteRepository voteRepository,
                ProjectBookmarkRepository bookmarkRepository
        ) {
            List<Long> ids = projects.stream().map(Project::getId).toList();
            if (ids.isEmpty()) return new FeedContext(Map.of(), Map.of(), Set.of(), Set.of());

            Map<Long, Long> voteCounts = new HashMap<>();
            for (ProjectVoteRepository.ProjectVoteCount row : voteRepository.countGroupedByProjectIdIn(ids)) {
                voteCounts.put(row.getProjectId(), row.getVoteCount());
            }
            Map<Long, Long> forkCounts = new HashMap<>();
            for (ProjectRepository.ProjectForkCount row : projectRepository.countGroupedByForkedFromIdIn(ids)) {
                forkCounts.put(row.getProjectId(), row.getForkCount());
            }
            // requester is null for an anonymous visitor viewing a public profile
            // (toFeedItems' other callers always pass the authenticated owner).
            Set<Long> votedProjectIds = requester == null
                    ? Set.of()
                    : voteRepository.findByUserIdAndProjectIdIn(requester.getId(), ids).stream()
                    .map(vote -> vote.getProject().getId())
                    .collect(Collectors.toSet());
            Set<Long> bookmarkedProjectIds = requester == null
                    ? Set.of()
                    : bookmarkRepository.findByUserIdAndProjectIdIn(requester.getId(), ids).stream()
                    .map(bookmark -> bookmark.getProject().getId())
                    .collect(Collectors.toSet());
            return new FeedContext(voteCounts, forkCounts, votedProjectIds, bookmarkedProjectIds);
        }
    }

    private ProjectFeedItemDTO toFeedItem(Project project, FeedContext ctx) {
        return new ProjectFeedItemDTO(
                project.getId(),
                project.getTitle(),
                project.getDescription(),
                project.getOwner().getUsername(),
                project.getThumbnail(),
                project.getTags(),
                project.getModifiedDate(),
                ctx.voteCounts().getOrDefault(project.getId(), 0L),
                ctx.votedProjectIds().contains(project.getId()),
                ctx.bookmarkedProjectIds().contains(project.getId()),
                project.getViewCount(),
                ctx.forkCounts().getOrDefault(project.getId(), 0L),
                project.isFeatured()
        );
    }

    private ForkFeedItemDTO toForkFeedItem(Project project, String ownerUsername, FeedContext ctx) {
        Project source = project.getForkedFrom();
        return new ForkFeedItemDTO(
                project.getId(),
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
                project.isFeatured(),
                source != null ? source.getId() : null,
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

    // In-memory rather than a SQL GROUP BY since tags live as a flat
    // comma-separated string, not a normalized join table — fine at the
    // scale of "published projects on a side project," not fine at scale.
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
        return new PublicIdeaDTO(idea.getId(), idea.getTitle(), idea.getType(), content);
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
                project.getId(),
                project.getTitle(),
                project.getDescription(),
                project.getVisibility(),
                project.getThumbnail(),
                project.getTags(),
                project.getCreationDate(),
                project.getModifiedDate()
        );
    }
}
