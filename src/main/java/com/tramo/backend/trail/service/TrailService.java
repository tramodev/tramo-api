package com.tramo.backend.trail.service;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.trail.dto.TrailRequestDTO;
import com.tramo.backend.trail.dto.TrailResponseDTO;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.entity.AssociationTargetType;
import com.tramo.backend.trail.repository.AssociationRepository;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.service.ProjectService;
import com.tramo.backend.user.entity.User;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class TrailService {
    private final TrailRepository trailRepository;
    private final TrailItemRepository trailItemRepository;
    private final ItemRepository itemRepository;
    private final AssociationRepository itemLinkRepository;
    private final ProjectService projectService;
    private final ProjectIdCodec projectIdCodec;

    public TrailService(TrailRepository trailRepository, TrailItemRepository trailItemRepository,
                        ItemRepository itemRepository, AssociationRepository itemLinkRepository,
                        ProjectService projectService, ProjectIdCodec projectIdCodec) {
        this.trailRepository = trailRepository;
        this.trailItemRepository = trailItemRepository;
        this.itemRepository = itemRepository;
        this.itemLinkRepository = itemLinkRepository;
        this.projectService = projectService;
        this.projectIdCodec = projectIdCodec;
    }

    public TrailResponseDTO create(Long projectId, TrailRequestDTO request, User requester) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        Project project = projectService.getOwnedProject(projectId, requester);
        Trail trail = new Trail();
        trail.setTitle(request.getTitle());
        trail.setDescription(request.getDescription());
        trail.setVisibility(request.getVisibility());
        trail.setProject(project);
        trail.setCreationDate(new Date());
        trail.setModifiedDate(new Date());
        return toResponse(trailRepository.save(trail));
    }

    public List<TrailResponseDTO> getAllForProject(Long projectId, User requester) {
        projectService.getOwnedProject(projectId, requester);
        return trailRepository.findByProjectId(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    public TrailResponseDTO getById(Long id, User requester) {
        return toResponse(getOwnedTrail(id, requester));
    }

    public TrailResponseDTO update(Long id, TrailRequestDTO request, User requester) {
        Trail trail = getOwnedTrail(id, requester);
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            trail.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            trail.setDescription(request.getDescription());
        }
        if (request.getVisibility() != null) {
            trail.setVisibility(request.getVisibility());
        }
        trail.setModifiedDate(new Date());
        return toResponse(trailRepository.save(trail));
    }

    @Transactional
    public void delete(Long id, User requester) {
        Trail trail = getOwnedTrail(id, requester);
        List<TrailItem> memberships = trailItemRepository.findByTrailIdOrderByOrderIndexAsc(id);
        for (TrailItem membership : memberships) {
            Item item = membership.getItem();
            trailItemRepository.delete(membership);
            if (trailItemRepository.findByItemId(item.getId()).isEmpty()) {
                if (item.getProject() == null) {
                    // Legacy item with no other trail: delete it and its links.
                    itemLinkRepository.deleteBySourceItemId(item.getId());
                    itemLinkRepository.deleteByTargetTypeAndTargetId(AssociationTargetType.ITEM, item.getId());
                    itemRepository.delete(item);
                } else {
                    // Keep it; it surfaces in Unfiled.
                    item.setUnfiled(true);
                    itemRepository.save(item);
                }
            }
        }
        // Drop associations that pointed at this trail as a whole.
        itemLinkRepository.deleteByTargetTypeAndTargetId(AssociationTargetType.TRAIL, id);
        trailRepository.delete(trail);
    }

    public Trail getOwnedTrail(Long id, User requester) {
        Trail trail = trailRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Trail not found"));
        if (!trail.getProject().getOwner().getId().equals(requester.getId())) {
            throw new AccessDeniedException("Not allowed to access this trail");
        }
        return trail;
    }

    private TrailResponseDTO toResponse(Trail trail) {
        return new TrailResponseDTO(
                trail.getId(),
                trail.getTitle(),
                trail.getDescription(),
                trail.getVisibility(),
                trail.getCreationDate(),
                trail.getModifiedDate(),
                projectIdCodec.encode(trail.getProject().getId()),
                trail.getVersion(),
                trail.getForkedFrom() != null ? trail.getForkedFrom().getId() : null
        );
    }
}
