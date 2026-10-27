// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.notification.service.NotificationService;
import com.tramo.backend.common.Pagination;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.RequestValidationException;
import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.sql.Timestamp;
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
    private final ProjectIdCodec projectIdCodec;

    public ExploreService(ProjectRepository projectRepository, ProjectVoteRepository projectVoteRepository,
                           FollowRepository followRepository, NotificationService notificationService,
                           ProjectFeedMapper feedMapper, PrivacyPolicy privacyPolicy, TagService tagService, ProjectIdCodec projectIdCodec) {
        this.projectRepository = projectRepository;
        this.projectVoteRepository = projectVoteRepository;
        this.followRepository = followRepository;
        this.notificationService = notificationService;
        this.feedMapper = feedMapper;
        this.privacyPolicy = privacyPolicy;
        this.tagService = tagService;
        this.projectIdCodec = projectIdCodec;
    }

    public ExploreBundleDTO getExploreBundle(String query, String sort, int page, int size, String cursor, User requester) {
        if (!List.of("recent", "hot", "following").contains(sort)) throw new RequestValidationException(RequestErrorCode.BAD_REQUEST);
        Position after = parseCursor(cursor, sort);
        if (after != null && page != 0) throw new RequestValidationException(RequestErrorCode.BAD_REQUEST);
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.length() < 3 && !q.contains("%") && !q.contains("_")) q = "";
        q = q.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        Pageable pageable = Pagination.page(page, size);
        Long viewerId = requester == null ? null : requester.getId();
        Set<String> blockRelated = privacyPolicy.blockRelatedUsernames(requester);

        boolean following = "following".equals(sort);
        List<Long> owners = following && requester != null ? followRepository.findFollowedIds(requester.getId()) : List.of();
        var rows = projectRepository.findExplorePage(ProjectVisibility.PUBLISHED, q, viewerId, following,
                owners.isEmpty() ? List.of(-1L) : owners, "hot".equals(sort),
                after == null ? null : after.id(), after == null ? null : after.date(), after == null ? 0 : after.votes(), pageable);
        List<Long> ids = rows.getContent().stream().map(ProjectRepository.ExploreRow::getId).toList();
        Map<Long, Project> byId = ids.isEmpty() ? Map.of() : projectRepository.findAllByIdInWithFetch(ids).stream()
                .collect(Collectors.toMap(Project::getId, project -> project));
        List<Project> pageProjects = ids.stream().map(byId::get)
                .filter(project -> project != null && project.getVisibility() == ProjectVisibility.PUBLISHED
                        && !project.getOwner().isBanned() && !blockRelated.contains(project.getOwner().getUsername())).toList();
        boolean hasMore = rows.hasNext();
        String nextCursor = hasMore && !rows.isEmpty() ? encodeCursor(rows.getContent().get(rows.getNumberOfElements() - 1), sort) : null;

        FeedContext ctx = feedMapper.publishedContextFor(pageProjects, requester);
        List<ProjectFeedItemDTO> feed = pageProjects.stream()
                .filter(project -> ctx.latestPublishByProjectId().containsKey(project.getId()))
                .map(project -> feedMapper.toFeedItem(project, ctx, blockRelated))
                .toList();

        ProjectFeedItemDTO featured = null;
        List<TagCountDTO> hotTopics = List.of();
        List<AuthorCountDTO> activeAuthors = List.of();
        List<ProjectFeedItemDTO> trendingProjects = List.of();
        if (page == 0 && cursor == null) {
            if (!"following".equals(sort)) {
                featured = projectRepository.findByFeaturedTrue()
                        .filter(project -> project.getVisibility() == ProjectVisibility.PUBLISHED)
                        .filter(project -> !blockRelated.contains(project.getOwner().getUsername()))
                        .flatMap(project -> feedMapper.toPublishedFeedItems(List.of(project), requester).stream().findFirst())
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

        return new ExploreBundleDTO(feed, hasMore, nextCursor, featured, hotTopics, activeAuthors, trendingProjects);
    }

    private record Position(Long id, Date date, long votes) {}

    private Position parseCursor(String cursor, String sort) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 256) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 4 || !parts[0].equals(sort)) throw new IllegalArgumentException();
            long votes = Long.parseLong(parts[3]);
            Long id = projectIdCodec.decode(parts[1]);
            Instant date = parts[2].equals("-") ? null : Instant.parse(parts[2]);
            if (votes < 0 || (date != null && (date.getEpochSecond() < 0 || date.getEpochSecond() > 253402300799L))) throw new IllegalArgumentException();
            return new Position(id, date == null ? null : Timestamp.from(date), votes);
        } catch (IllegalArgumentException | DateTimeParseException | ResourceNotFoundException invalid) {
            throw new RequestValidationException(RequestErrorCode.BAD_REQUEST);
        }
    }

    private String encodeCursor(ProjectRepository.ExploreRow row, String sort) {
        String value = sort + "|" + projectIdCodec.encode(row.getId()) + "|"
                + (row.getPublishedAt() == null ? "-" : row.getPublishedAt().toInstant()) + "|" + row.getVoteCount();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
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
        return projects.stream().filter(project -> ctx.latestPublishByProjectId().containsKey(project.getId()))
                .map(project -> feedMapper.toFeedItem(project, ctx)).toList();
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
