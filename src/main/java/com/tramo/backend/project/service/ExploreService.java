package com.tramo.backend.project.service;

import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.project.dto.AuthorCountDTO;
import com.tramo.backend.project.dto.ExploreBundleDTO;
import com.tramo.backend.project.dto.ProjectFeedItemDTO;
import com.tramo.backend.project.dto.TagCountDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectVoteRepository;
import com.tramo.backend.tag.service.TagService;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.FollowRepository;
import com.tramo.backend.user.service.PrivacyPolicy;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ExploreService {
    private static final Logger log = LoggerFactory.getLogger(ExploreService.class);
    private static final long EXPLORE_CACHE_REFRESH_MS = 5 * 60 * 1000;

    private volatile List<TagCountDTO> cachedHotTopics = List.of();
    private volatile List<AuthorCountDTO> cachedActiveAuthors = List.of();
    private volatile List<ProjectFeedItemDTO> cachedTrendingProjects = List.of();

    private final ProjectRepository projectRepository;
    private final ProjectVoteRepository projectVoteRepository;
    private final FollowRepository followRepository;
    private final NotificationService notificationService;
    private final ProjectFeedMapper feedMapper;
    private final PrivacyPolicy privacyPolicy;
    private final TagService tagService;

    public ExploreService(ProjectRepository projectRepository, ProjectVoteRepository projectVoteRepository,
                           FollowRepository followRepository, NotificationService notificationService,
                           ProjectFeedMapper feedMapper, PrivacyPolicy privacyPolicy, TagService tagService) {
        this.projectRepository = projectRepository;
        this.projectVoteRepository = projectVoteRepository;
        this.followRepository = followRepository;
        this.notificationService = notificationService;
        this.feedMapper = feedMapper;
        this.privacyPolicy = privacyPolicy;
        this.tagService = tagService;
    }

    public ExploreBundleDTO getExploreBundle(String query, String sort, int page, int size, User requester) {
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.length() < 3) q = "";
        Pageable pageable = PageRequest.of(page, size);
        Long viewerId = requester == null ? null : requester.getId();
        Set<String> blockRelated = privacyPolicy.blockRelatedUsernames(requester);

        List<Project> pageProjects;
        boolean hasMore;
        if ("hot".equals(sort)) {
            Page<Long> idPage = projectRepository.findPublishedHotIds(ProjectVisibility.PUBLISHED, q, viewerId, pageable);
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
                Page<Project> projectPage = projectRepository.findPublishedRecentByOwners(ProjectVisibility.PUBLISHED, followedIds, q, viewerId, pageable);
                pageProjects = projectPage.getContent();
                hasMore = projectPage.hasNext();
            }
        } else {
            Page<Project> projectPage = projectRepository.findPublishedRecent(ProjectVisibility.PUBLISHED, q, viewerId, pageable);
            pageProjects = projectPage.getContent();
            hasMore = projectPage.hasNext();
        }

        FeedContext ctx = feedMapper.publishedContextFor(pageProjects, requester);
        List<ProjectFeedItemDTO> feed = pageProjects.stream()
                .map(project -> feedMapper.toFeedItem(project, ctx, blockRelated))
                .toList();

        ProjectFeedItemDTO featured = null;
        List<TagCountDTO> hotTopics = List.of();
        List<AuthorCountDTO> activeAuthors = List.of();
        List<ProjectFeedItemDTO> trendingProjects = List.of();
        if (page == 0) {
            if (!"following".equals(sort)) {
                featured = projectRepository.findByFeaturedTrue()
                        .filter(project -> project.getVisibility() == ProjectVisibility.PUBLISHED)
                        .filter(project -> !blockRelated.contains(project.getOwner().getUsername()))
                        .map(project -> feedMapper.toFeedItem(project, feedMapper.publishedContextFor(List.of(project), requester)))
                        .orElse(null);
            }
            hotTopics = cachedHotTopics;
            activeAuthors = blockRelated.isEmpty()
                    ? cachedActiveAuthors
                    : cachedActiveAuthors.stream()
                        .filter(author -> !blockRelated.contains(author.getUsername()))
                        .toList();
            trendingProjects = blockRelated.isEmpty()
                    ? cachedTrendingProjects
                    : cachedTrendingProjects.stream()
                        .filter(item -> !blockRelated.contains(item.getOwnerUsername()))
                        .toList();
        }

        return new ExploreBundleDTO(feed, hasMore, featured, hotTopics, activeAuthors, trendingProjects);
    }

    public List<TagCountDTO> getHotTopics(int limit) {
        return tagService.hotTopics(limit).stream()
                .map(tag -> new TagCountDTO(tag.getName(), tag.getUsageCount()))
                .toList();
    }

    public List<AuthorCountDTO> getActiveAuthors(int limit) {
        return projectRepository.findActiveAuthors(ProjectVisibility.PUBLISHED, PageRequest.of(0, limit)).stream()
                .map(a -> new AuthorCountDTO(a.getUsername(), a.getAvatar(), a.getCount()))
                .toList();
    }

    public List<ProjectFeedItemDTO> getTrendingProjects(int limit) {
        Page<Long> idPage = projectRepository.findPublishedHotIds(ProjectVisibility.PUBLISHED, "", null, PageRequest.of(0, limit));
        List<Long> ids = idPage.getContent();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Project> byId = projectRepository.findAllByIdInWithFetch(ids).stream()
                .collect(Collectors.toMap(Project::getId, p -> p));
        List<Project> projects = ids.stream().map(byId::get).toList();
        FeedContext ctx = feedMapper.publishedContextFor(projects, null);
        return projects.stream().map(project -> feedMapper.toFeedItem(project, ctx)).toList();
    }

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void refreshFeaturedProject() {
        Optional<ProjectVoteRepository.TrustedVoteCount> topVoted = projectVoteRepository.findTopTrustedPublished();
        Optional<Project> topProject = topVoted
                .flatMap(row -> projectRepository.findById(row.getProjectId()))
                .or(() -> projectRepository.findFirstByVisibilityOrderByLastPublishedDateDesc(ProjectVisibility.PUBLISHED));
        if (topProject.isEmpty()) return;

        Project top = topProject.get();
        log.info("Featured pick: project {} with {} trusted votes ({} raw)",
                top.getId(),
                topVoted.map(ProjectVoteRepository.TrustedVoteCount::getTrustedCount).orElse(0L),
                topVoted.map(ProjectVoteRepository.TrustedVoteCount::getRawCount).orElse(0L));

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
        cachedTrendingProjects = getTrendingProjects(5);
    }
}
