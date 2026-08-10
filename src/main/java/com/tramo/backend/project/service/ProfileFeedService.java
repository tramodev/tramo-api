package com.tramo.backend.project.service;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.project.dto.ActivityItemDTO;
import com.tramo.backend.project.dto.ForkFeedItemDTO;
import com.tramo.backend.project.dto.PageResponseDTO;
import com.tramo.backend.project.dto.ProjectFeedItemDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectBookmark;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.entity.ProjectVote;
import com.tramo.backend.project.repository.ProjectBookmarkRepository;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectVoteRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.service.PrivacyPolicy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class ProfileFeedService {
    private static final int ACTIVITY_FEED_LIMIT = 50;

    private final AccessGuard accessGuard;
    private final ProjectRepository projectRepository;
    private final ProjectVoteRepository projectVoteRepository;
    private final ProjectBookmarkRepository projectBookmarkRepository;
    private final ProjectFeedMapper feedMapper;
    private final PrivacyPolicy privacyPolicy;
    private final ProjectIdCodec projectIdCodec;

    public ProfileFeedService(AccessGuard accessGuard, ProjectRepository projectRepository,
                               ProjectVoteRepository projectVoteRepository,
                               ProjectBookmarkRepository projectBookmarkRepository, ProjectFeedMapper feedMapper,
                               PrivacyPolicy privacyPolicy, ProjectIdCodec projectIdCodec) {
        this.accessGuard = accessGuard;
        this.projectRepository = projectRepository;
        this.projectVoteRepository = projectVoteRepository;
        this.projectBookmarkRepository = projectBookmarkRepository;
        this.feedMapper = feedMapper;
        this.privacyPolicy = privacyPolicy;
        this.projectIdCodec = projectIdCodec;
    }

    public PageResponseDTO<ProjectFeedItemDTO> getPublishedPage(User user, int page, int size) {
        Page<Project> result = projectRepository.findByOwnerIdAndVisibilityOrderByCreationDateDescPaged(
                user.getId(), ProjectVisibility.PUBLISHED, PageRequest.of(page, size));
        return new PageResponseDTO<>(feedMapper.toPublishedFeedItems(result.getContent(), user), result.hasNext());
    }

    public PageResponseDTO<ProjectFeedItemDTO> getBookmarksPage(User user, int page, int size) {
        Page<ProjectBookmark> result = projectBookmarkRepository.findByUserIdOrderByCreatedDateDescPaged(
                user.getId(), PageRequest.of(page, size));
        List<Project> projects = result.getContent().stream().map(ProjectBookmark::getProject).toList();
        return new PageResponseDTO<>(feedMapper.toFeedItems(projects, user), result.hasNext());
    }

    public PageResponseDTO<ProjectFeedItemDTO> getUpvotedPage(User user, int page, int size) {
        return upvotedPage(user.getId(), user, page, size, false);
    }

    private PageResponseDTO<ProjectFeedItemDTO> upvotedPage(Long targetId, User requester, int page, int size, boolean publishedOnly) {
        Page<ProjectVote> result = projectVoteRepository.findByUserIdOrderByCreatedDateDescPaged(
                targetId, PageRequest.of(page, size));
        List<Project> projects = result.getContent().stream()
                .map(ProjectVote::getProject)
                .filter(p -> !publishedOnly
                        || (p.getVisibility() == ProjectVisibility.PUBLISHED && !p.getOwner().isBanned()))
                .toList();
        return new PageResponseDTO<>(feedMapper.toFeedItems(projects, requester), result.hasNext());
    }

    public PageResponseDTO<ForkFeedItemDTO> getForksPage(User user, int page, int size) {
        Page<Project> result = projectRepository.findByOwnerIdAndForkedFromNotNullOrderByCreationDateDescPaged(
                user.getId(), PageRequest.of(page, size));
        return new PageResponseDTO<>(feedMapper.toForkFeedItems(result.getContent(), user), result.hasNext());
    }

    public PageResponseDTO<ActivityItemDTO> getActivityPage(User user, int page, int size) {
        Pageable cap = PageRequest.of(0, Math.min((page + 1) * size, ACTIVITY_FEED_LIMIT));
        List<ProjectBookmark> myBookmarks = projectBookmarkRepository.findByUserIdOrderByCreatedDateDesc(user.getId(), cap);
        List<ProjectVote> myVotes = projectVoteRepository.findByUserIdOrderByCreatedDateDesc(user.getId(), cap);
        List<Project> myForkedProjects = projectRepository.findByOwnerIdAndForkedFromNotNullOrderByCreationDateDesc(user.getId(), cap);
        List<Project> myPublishedProjects = projectRepository.findByOwnerIdAndVisibilityOrderByCreationDateDesc(user.getId(), ProjectVisibility.PUBLISHED, cap);
        List<ActivityItemDTO> all = getMyActivity(user, myBookmarks, myVotes, myForkedProjects, myPublishedProjects, cap);

        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return new PageResponseDTO<>(all.subList(from, to), to < all.size());
    }

    private List<ActivityItemDTO> getMyActivity(User user, List<ProjectBookmark> myBookmarks, List<ProjectVote> myVotes, List<Project> myForkedProjects, List<Project> myPublishedProjects, Pageable cap) {
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
        for (ProjectVote vote : projectVoteRepository.findByProjectOwnerIdAndUserIdNotOrderByCreatedDateDesc(userId, userId, cap)) {
            items.add(new ActivityItemDTO("received_vote", vote.getCreatedDate(), projectIdCodec.encode(vote.getProject().getId()), vote.getProject().getTitle(), vote.getUser().getUsername()));
        }
        for (Project project : projectRepository.findByForkedFromOwnerIdAndOwnerIdNotOrderByCreationDateDesc(userId, userId, cap)) {
            Project source = project.getForkedFrom();
            items.add(new ActivityItemDTO("received_fork", project.getCreationDate(), projectIdCodec.encode(source.getId()), source.getTitle(), project.getOwner().getUsername()));
        }
        for (ProjectBookmark bookmark : projectBookmarkRepository.findByProjectOwnerIdAndUserIdNotOrderByCreatedDateDesc(userId, userId, cap)) {
            items.add(new ActivityItemDTO("received_bookmark", bookmark.getCreatedDate(), projectIdCodec.encode(bookmark.getProject().getId()), bookmark.getProject().getTitle(), bookmark.getUser().getUsername()));
        }

        Set<String> blockRelated = privacyPolicy.blockRelatedUsernames(user);
        return items.stream()
                .filter(item -> item.getOtherUsername() == null || !blockRelated.contains(item.getOtherUsername()))
                .sorted(Comparator.comparing(ActivityItemDTO::getTimestamp).reversed())
                .limit(ACTIVITY_FEED_LIMIT)
                .toList();
    }

    public PageResponseDTO<ProjectFeedItemDTO> getPublishedPageForUser(String username, User requester, int page, int size) {
        User target = accessGuard.publicProfileTarget(username, requester);
        Page<Project> result = projectRepository.findByOwnerIdAndVisibilityOrderByCreationDateDescPaged(
                target.getId(), ProjectVisibility.PUBLISHED, PageRequest.of(page, size));
        return new PageResponseDTO<>(feedMapper.toPublishedFeedItems(result.getContent(), requester), result.hasNext());
    }

    public PageResponseDTO<ProjectFeedItemDTO> getPublicUpvotedPage(String username, User requester, int page, int size) {
        User target = accessGuard.publicProfileTarget(username, requester);
        boolean self = requester != null && requester.getId().equals(target.getId());
        if (!self && Boolean.FALSE.equals(target.getShowUpvotes())) {
            throw new ResourceNotFoundException("User not found");
        }
        return upvotedPage(target.getId(), requester, page, size, true);
    }
}
