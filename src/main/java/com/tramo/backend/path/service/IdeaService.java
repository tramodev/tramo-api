package com.tramo.backend.path.service;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.path.dto.IdeaContentResponseDTO;
import com.tramo.backend.path.dto.IdeaRequestDTO;
import com.tramo.backend.path.dto.IdeaResponseDTO;
import com.tramo.backend.path.entity.Idea;
import com.tramo.backend.path.entity.IdeaContent;
import com.tramo.backend.path.entity.IdeaLink;
import com.tramo.backend.path.entity.Path;
import com.tramo.backend.path.entity.PathIdea;
import com.tramo.backend.path.repository.IdeaLinkRepository;
import com.tramo.backend.path.repository.IdeaRepository;
import com.tramo.backend.path.repository.PathIdeaRepository;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.upload.R2Client;
import com.tramo.backend.user.entity.User;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Set;

@Service
public class IdeaService {
    private static final Logger log = LoggerFactory.getLogger(IdeaService.class);

    private final IdeaRepository ideaRepository;
    private final PathIdeaRepository pathIdeaRepository;
    private final IdeaLinkRepository ideaLinkRepository;
    private final PathService pathService;
    private final ProjectRepository projectRepository;
    private final R2Client r2Client;

    public IdeaService(IdeaRepository ideaRepository, PathIdeaRepository pathIdeaRepository,
                        IdeaLinkRepository ideaLinkRepository, PathService pathService,
                        ProjectRepository projectRepository, R2Client r2Client) {
        this.ideaRepository = ideaRepository;
        this.pathIdeaRepository = pathIdeaRepository;
        this.ideaLinkRepository = ideaLinkRepository;
        this.pathService = pathService;
        this.projectRepository = projectRepository;
        this.r2Client = r2Client;
    }

    public IdeaResponseDTO create(Long pathId, IdeaRequestDTO request, User requester) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        Path path = pathService.getOwnedPath(pathId, requester);

        IdeaContent content = new IdeaContent();
        content.setContent("");
        content.setUpdatedDate(new Date());

        Idea idea = new Idea();
        idea.setTitle(request.getTitle());
        idea.setType(request.getType());
        idea.setTitleAlign("center");
        idea.setContent(content);
        idea.setCreatedDate(new Date());
        idea.setModifiedDate(new Date());
        idea = ideaRepository.save(idea);

        PathIdea pathIdea = new PathIdea();
        pathIdea.setPath(path);
        pathIdea.setIdea(idea);
        pathIdea.setOrderIndex(pathIdeaRepository.countByPathId(pathId));
        pathIdeaRepository.save(pathIdea);

        return toResponse(idea);
    }

    public List<IdeaResponseDTO> getAllForPath(Long pathId, User requester) {
        pathService.getOwnedPath(pathId, requester);
        return pathIdeaRepository.findByPathIdOrderByOrderIndexAsc(pathId).stream()
                .map(pi -> toResponse(pi.getIdea()))
                .toList();
    }

    public IdeaResponseDTO update(Long id, IdeaRequestDTO request, User requester) {
        Idea idea = getOwnedIdea(id, requester);
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            idea.setTitle(request.getTitle());
        }
        if (request.getType() != null) {
            idea.setType(request.getType());
        }
        if (request.getTitleAlign() != null) {
            idea.setTitleAlign(request.getTitleAlign());
        }
        idea.setModifiedDate(new Date());
        return toResponse(ideaRepository.save(idea));
    }

    @Transactional
    public void delete(Long id, User requester) {
        Idea idea = getOwnedIdea(id, requester);
        deleteIdeaCompletely(idea);
    }

    private void deleteIdeaCompletely(Idea idea) {
        ideaLinkRepository.deleteBySourceIdeaIdOrTargetIdeaId(idea.getId(), idea.getId());
        pathIdeaRepository.deleteAll(pathIdeaRepository.findByIdeaId(idea.getId()));
        ideaRepository.delete(idea);
    }

    public IdeaContentResponseDTO getContent(Long id, User requester) {
        Idea idea = getOwnedIdea(id, requester);
        String content = idea.getContent() != null ? idea.getContent().getContent() : "";
        return new IdeaContentResponseDTO(content);
    }

    public void updateContent(Long id, String content, User requester) {
        Idea idea = getOwnedIdea(id, requester);
        IdeaContent ideaContent = idea.getContent();
        String previousContent = ideaContent != null ? ideaContent.getContent() : null;
        if (ideaContent == null) {
            ideaContent = new IdeaContent();
            idea.setContent(ideaContent);
        }
        ideaContent.setContent(content);
        ideaContent.setUpdatedDate(new Date());
        ideaRepository.save(idea);
        bumpOwningProjectLastEditedDate(id);
        deleteOrphanedEditorImages(id, requester, previousContent, content);
    }

    private void deleteOrphanedEditorImages(Long ideaId, User requester, String previousContent, String newContent) {
        Set<String> oldUrls = r2Client.extractReferencedUrls(previousContent);
        Set<String> newUrls = r2Client.extractReferencedUrls(newContent);
        for (String url : oldUrls) {
            if (newUrls.contains(url)) {
                continue;
            }
            if (!pathIdeaRepository.existsOtherIdeaReferencingUrl(requester.getId(), url, ideaId)) {
                log.info("deleteOrphanedEditorImages deleting url={} idea={}", url, ideaId);
                r2Client.deleteByPublicUrl(url);
            }
        }
    }

    private void bumpOwningProjectLastEditedDate(Long ideaId) {
        pathIdeaRepository.findByIdeaId(ideaId).stream().findFirst().ifPresent(pathIdea -> {
            Project project = pathIdea.getPath().getProject();
            project.setLastEditedDate(new Date());
            projectRepository.save(project);
        });
    }

    public void attachToPath(Long pathId, Long ideaId, User requester) {
        Path path = pathService.getOwnedPath(pathId, requester);
        Idea idea = getOwnedIdea(ideaId, requester);
        boolean alreadyAttached = pathIdeaRepository.findByIdeaId(idea.getId()).stream()
                .anyMatch(pi -> pi.getPath().getId().equals(path.getId()));
        if (alreadyAttached) {
            return;
        }
        PathIdea pathIdea = new PathIdea();
        pathIdea.setPath(path);
        pathIdea.setIdea(idea);
        pathIdea.setOrderIndex(pathIdeaRepository.countByPathId(pathId));
        pathIdeaRepository.save(pathIdea);
    }

    @Transactional
    public void detachFromPath(Long pathId, Long ideaId, User requester) {
        pathService.getOwnedPath(pathId, requester);
        Idea idea = getOwnedIdea(ideaId, requester);

        pathIdeaRepository.findByPathIdOrderByOrderIndexAsc(pathId).stream()
                .filter(pi -> pi.getIdea().getId().equals(idea.getId()))
                .findFirst()
                .ifPresent(pathIdeaRepository::delete);

        if (pathIdeaRepository.findByIdeaId(idea.getId()).isEmpty()) {
            deleteIdeaCompletely(idea);
        }
    }

    public void linkIdeas(Long sourceId, Long targetId, User requester) {
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("An idea cannot be linked to itself");
        }
        Idea source = getOwnedIdea(sourceId, requester);
        Idea target = getOwnedIdea(targetId, requester);

        if (ideaLinkRepository.findBySourceIdeaIdAndTargetIdeaId(source.getId(), target.getId()).isPresent()
                || ideaLinkRepository.findBySourceIdeaIdAndTargetIdeaId(target.getId(), source.getId()).isPresent()) {
            return;
        }

        IdeaLink link = new IdeaLink();
        link.setSourceIdea(source);
        link.setTargetIdea(target);
        link.setCreatedDate(new Date());
        ideaLinkRepository.save(link);
    }

    public void unlinkIdeas(Long sourceId, Long targetId, User requester) {
        getOwnedIdea(sourceId, requester);
        getOwnedIdea(targetId, requester);
        ideaLinkRepository.findBySourceIdeaIdAndTargetIdeaId(sourceId, targetId).ifPresent(ideaLinkRepository::delete);
        ideaLinkRepository.findBySourceIdeaIdAndTargetIdeaId(targetId, sourceId).ifPresent(ideaLinkRepository::delete);
    }

    public List<IdeaResponseDTO> getLinkedIdeas(Long id, User requester) {
        Idea idea = getOwnedIdea(id, requester);
        return ideaLinkRepository.findBySourceIdeaIdOrTargetIdeaId(idea.getId(), idea.getId()).stream()
                .map(link -> link.getSourceIdea().getId().equals(idea.getId()) ? link.getTargetIdea() : link.getSourceIdea())
                .map(this::toResponse)
                .toList();
    }

    private Idea getOwnedIdea(Long id, User requester) {
        Idea idea = ideaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Idea not found"));
        boolean owns = pathIdeaRepository.findByIdeaId(id).stream()
                .anyMatch(pi -> pi.getPath().getProject().getOwner().getId().equals(requester.getId()));
        if (!owns) {
            throw new AccessDeniedException("Not allowed to access this idea");
        }
        return idea;
    }

    private IdeaResponseDTO toResponse(Idea idea) {
        return new IdeaResponseDTO(
                idea.getId(),
                idea.getTitle(),
                idea.getType(),
                idea.getTitleAlign(),
                idea.getCreatedDate(),
                idea.getModifiedDate()
        );
    }
}
