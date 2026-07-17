package com.mypath.backend.path.service;

import com.mypath.backend.common.ProjectIdCodec;
import com.mypath.backend.exception.ResourceNotFoundException;
import com.mypath.backend.path.dto.PathRequestDTO;
import com.mypath.backend.path.dto.PathResponseDTO;
import com.mypath.backend.path.entity.Path;
import com.mypath.backend.path.entity.PathIdea;
import com.mypath.backend.path.repository.IdeaLinkRepository;
import com.mypath.backend.path.repository.IdeaRepository;
import com.mypath.backend.path.repository.PathIdeaRepository;
import com.mypath.backend.path.repository.PathRepository;
import com.mypath.backend.project.entity.Project;
import com.mypath.backend.project.service.ProjectService;
import com.mypath.backend.user.entity.User;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class PathService {
    private final PathRepository pathRepository;
    private final PathIdeaRepository pathIdeaRepository;
    private final IdeaRepository ideaRepository;
    private final IdeaLinkRepository ideaLinkRepository;
    private final ProjectService projectService;
    private final ProjectIdCodec projectIdCodec;

    public PathService(PathRepository pathRepository, PathIdeaRepository pathIdeaRepository,
                        IdeaRepository ideaRepository, IdeaLinkRepository ideaLinkRepository,
                        ProjectService projectService, ProjectIdCodec projectIdCodec) {
        this.pathRepository = pathRepository;
        this.pathIdeaRepository = pathIdeaRepository;
        this.ideaRepository = ideaRepository;
        this.ideaLinkRepository = ideaLinkRepository;
        this.projectService = projectService;
        this.projectIdCodec = projectIdCodec;
    }

    public PathResponseDTO create(Long projectId, PathRequestDTO request, User requester) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        Project project = projectService.getOwnedProject(projectId, requester);
        Path path = new Path();
        path.setTitle(request.getTitle());
        path.setVisibility(request.getVisibility());
        path.setProject(project);
        path.setCreationDate(new Date());
        path.setModifiedDate(new Date());
        return toResponse(pathRepository.save(path));
    }

    public List<PathResponseDTO> getAllForProject(Long projectId, User requester) {
        projectService.getOwnedProject(projectId, requester);
        return pathRepository.findByProjectId(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    public PathResponseDTO getById(Long id, User requester) {
        return toResponse(getOwnedPath(id, requester));
    }

    public PathResponseDTO update(Long id, PathRequestDTO request, User requester) {
        Path path = getOwnedPath(id, requester);
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            path.setTitle(request.getTitle());
        }
        if (request.getVisibility() != null) {
            path.setVisibility(request.getVisibility());
        }
        path.setModifiedDate(new Date());
        return toResponse(pathRepository.save(path));
    }

    @Transactional
    public void delete(Long id, User requester) {
        Path path = getOwnedPath(id, requester);
        List<PathIdea> memberships = pathIdeaRepository.findByPathIdOrderByOrderIndexAsc(id);
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

    public Path getOwnedPath(Long id, User requester) {
        Path path = pathRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Path not found"));
        if (!path.getProject().getOwner().getId().equals(requester.getId())) {
            throw new AccessDeniedException("Not allowed to access this path");
        }
        return path;
    }

    private PathResponseDTO toResponse(Path path) {
        return new PathResponseDTO(
                path.getId(),
                path.getTitle(),
                path.getVisibility(),
                path.getCreationDate(),
                path.getModifiedDate(),
                projectIdCodec.encode(path.getProject().getId())
        );
    }
}
