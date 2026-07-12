package com.mypath.backend.project.service;

import com.mypath.backend.exception.ResourceNotFoundException;
import com.mypath.backend.path.entity.Idea;
import com.mypath.backend.path.entity.IdeaContent;
import com.mypath.backend.path.entity.IdeaLink;
import com.mypath.backend.path.entity.Path;
import com.mypath.backend.path.entity.PathIdea;
import com.mypath.backend.path.repository.IdeaLinkRepository;
import com.mypath.backend.path.repository.IdeaRepository;
import com.mypath.backend.path.repository.PathIdeaRepository;
import com.mypath.backend.path.repository.PathRepository;
import com.mypath.backend.project.dto.ProjectFeedItemDTO;
import com.mypath.backend.project.dto.ProjectRequestDTO;
import com.mypath.backend.project.dto.ProjectResponseDTO;
import com.mypath.backend.project.dto.PublicIdeaDTO;
import com.mypath.backend.project.dto.PublicPathDTO;
import com.mypath.backend.project.dto.PublicProjectResponseDTO;
import com.mypath.backend.project.dto.TagCountDTO;
import com.mypath.backend.project.dto.VoteResponseDTO;
import com.mypath.backend.project.entity.Project;
import com.mypath.backend.project.entity.ProjectVote;
import com.mypath.backend.project.repository.ProjectRepository;
import com.mypath.backend.project.repository.ProjectVoteRepository;
import com.mypath.backend.user.entity.User;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProjectService {
    private final ProjectRepository projectRepository;
    private final PathRepository pathRepository;
    private final PathIdeaRepository pathIdeaRepository;
    private final IdeaRepository ideaRepository;
    private final ProjectVoteRepository projectVoteRepository;
    private final IdeaLinkRepository ideaLinkRepository;

    public ProjectService(ProjectRepository projectRepository, PathRepository pathRepository,
                           PathIdeaRepository pathIdeaRepository, IdeaRepository ideaRepository,
                           ProjectVoteRepository projectVoteRepository, IdeaLinkRepository ideaLinkRepository) {
        this.projectRepository = projectRepository;
        this.pathRepository = pathRepository;
        this.pathIdeaRepository = pathIdeaRepository;
        this.ideaRepository = ideaRepository;
        this.ideaLinkRepository = ideaLinkRepository;
        this.projectVoteRepository = projectVoteRepository;
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
        return toResponse(projectRepository.save(project));
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

    public PublicProjectResponseDTO getPublicProject(Long id, User requester) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        assertViewable(project);

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
                requester != null && projectVoteRepository.findByProjectIdAndUserId(id, requester.getId()).isPresent()
        );
    }

    public List<ProjectFeedItemDTO> getPublishedFeed(String query, String sort, User requester) {
        String q = query == null ? "" : query.trim().toLowerCase();
        List<Project> published = projectRepository.findByVisibilityOrderByModifiedDateDesc("published").stream()
                .filter(project -> q.isEmpty() || matchesSearch(project, q))
                .toList();

        Set<Long> votedProjectIds = requester == null
                ? Set.of()
                : projectVoteRepository.findByUserIdAndProjectIdIn(
                        requester.getId(), published.stream().map(Project::getId).toList())
                .stream().map(vote -> vote.getProject().getId()).collect(Collectors.toSet());

        List<ProjectFeedItemDTO> feed = published.stream()
                .map(project -> new ProjectFeedItemDTO(
                        project.getId(),
                        project.getTitle(),
                        project.getDescription(),
                        project.getOwner().getUsername(),
                        project.getThumbnail(),
                        project.getTags(),
                        project.getModifiedDate(),
                        projectVoteRepository.countByProjectId(project.getId()),
                        votedProjectIds.contains(project.getId())
                ))
                .collect(Collectors.toList());

        if ("hot".equals(sort)) {
            feed.sort(Comparator.comparingLong(ProjectFeedItemDTO::getVoteCount).reversed()
                    .thenComparing(ProjectFeedItemDTO::getModifiedDate, Comparator.reverseOrder()));
        }
        return feed;
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
        }
        return new VoteResponseDTO(voted, projectVoteRepository.countByProjectId(projectId));
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
