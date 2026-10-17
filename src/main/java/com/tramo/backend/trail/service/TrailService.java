// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail.service;

import com.tramo.backend.project.service.ProjectVersionCapture;
import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.common.ContentLimits;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.trail.dto.TrailRequestDTO;
import com.tramo.backend.trail.dto.TrailResponseDTO;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectThumbnailType;
import com.tramo.backend.project.service.AccessGuard;
import com.tramo.backend.upload.ImageDeletionQueue;
import com.tramo.backend.user.entity.User;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.HashSet;
import java.util.stream.Collectors;

@Service
public class TrailService {
    private final TrailRepository trailRepository;
    private final TrailItemRepository trailItemRepository;
    private final ItemRepository itemRepository;
    private final AccessGuard accessGuard;
    private final ProjectIdCodec projectIdCodec;
    private final ImageDeletionQueue imageDeletionQueue;
    private final SubscriptionService subscriptions;
    private final ProjectVersionCapture history;

    public TrailService(TrailRepository trailRepository, TrailItemRepository trailItemRepository,
                        ItemRepository itemRepository,
                        AccessGuard accessGuard, ProjectIdCodec projectIdCodec,
                        ImageDeletionQueue imageDeletionQueue, SubscriptionService subscriptions, ProjectVersionCapture history) {
        this.trailRepository = trailRepository;
        this.trailItemRepository = trailItemRepository;
        this.itemRepository = itemRepository;
        this.accessGuard = accessGuard;
        this.projectIdCodec = projectIdCodec;
        this.imageDeletionQueue = imageDeletionQueue;
        this.subscriptions = subscriptions;
        this.history = history;
    }

    @Transactional
    public TrailResponseDTO create(Long projectId, TrailRequestDTO request, User requester) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new RequestValidationException(RequestErrorCode.TITLE_REQUIRED);
        }
        Project project = history.lock(projectId, requester);
        ContentLimits.assertTrailDescription(request.getDescription());
        long bytes = ContentLimits.bytes(request.getDescription());
        if (bytes > 0) subscriptions.assertUploadAllowed(requester, bytes, subscriptions.isSupporter(requester));
        history.beforeChange(project);
        Trail trail = new Trail();
        trail.setTitle(request.getTitle());
        trail.setDescription(request.getDescription());
        trail.setVisibility(request.getVisibility());
        trail.setProject(project);
        trail.setOrderIndex(trailRepository.nextOrderIndex(projectId));
        trail.setCreationDate(new Date());
        trail.setModifiedDate(new Date());
        return toResponse(trailRepository.save(trail));
    }

    public List<TrailResponseDTO> getAllForProject(Long projectId, User requester) {
        accessGuard.getOwnedProject(projectId, requester);
        return trailRepository.findByProjectId(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    public TrailResponseDTO getById(Long id, User requester) {
        return toResponse(getOwnedTrail(id, requester));
    }

    @Transactional
    public void reorder(Long projectId, List<Long> trailIds, User requester) {
        Project project = history.lock(projectId, requester);
        List<Trail> trails = trailRepository.findByProjectId(projectId);
        var byId = trails.stream().collect(Collectors.toMap(Trail::getId, trail -> trail));
        if (trailIds.size() != byId.size() || !byId.keySet().equals(new HashSet<>(trailIds))) {
            throw new RequestValidationException(RequestErrorCode.PROJECT_TRAIL_ORDER_INVALID);
        }
        if (trails.stream().map(Trail::getId).toList().equals(trailIds)) return;
        history.beforeChange(project);
        for (int index = 0; index < trailIds.size(); index++) byId.get(trailIds.get(index)).setOrderIndex(index);
        trailRepository.saveAll(trails);
    }

    @Transactional
    public TrailResponseDTO update(Long id, TrailRequestDTO request, User requester) {
        ContentLimits.assertTrailDescription(request.getDescription());
        Project project = history.lockTrail(id, requester);
        Trail trail = getOwnedTrail(id, requester);
        boolean changed = (request.getTitle() != null && !request.getTitle().isBlank() && !java.util.Objects.equals(request.getTitle(), trail.getTitle()))
                || (request.getDescription() != null && !java.util.Objects.equals(request.getDescription(), trail.getDescription()))
                || (request.getVisibility() != null && !java.util.Objects.equals(request.getVisibility(), trail.getVisibility()));
        if (!changed) return toResponse(trail);
        history.beforeChange(project);
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            trail.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            long growth = (long) ContentLimits.bytes(request.getDescription()) - ContentLimits.bytes(trail.getDescription());
            if (growth > 0) subscriptions.assertUploadAllowed(requester, growth, subscriptions.isSupporter(requester));
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
        Trail trail = getOwnedTrailForUpdate(id, requester);
        history.beforeChange(trail.getProject());
        clearThumbnailReference(trail);
        List<TrailItem> memberships = trailItemRepository.findByTrailIdOrderByOrderIndexAsc(id);
        for (TrailItem membership : memberships) {
            Item item = membership.getItem();
            trailItemRepository.delete(membership);
            if (trailItemRepository.findByItemId(item.getId()).isEmpty()) {
                if (item.getProject() == null) {

                    imageDeletionQueue.queueItemImages(item.getId(), requester.getId());
                    itemRepository.delete(item);
                } else {
                    
                    item.setUnfiled(true);
                    itemRepository.save(item);
                }
            }
        }
        
        trailRepository.delete(trail);
    }

    private void clearThumbnailReference(Trail trail) {
        Project project = trail.getProject();
        if (project.getThumbnailTrail() != null && project.getThumbnailTrail().getId().equals(trail.getId())) {
            project.setThumbnailTrail(null);
            project.setThumbnailType(ProjectThumbnailType.NONE);
        }
    }

    public Trail getOwnedTrailForUpdate(Long id, User requester) {
        history.lockTrail(id, requester);
        trailRepository.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Trail not found"));
        return getOwnedTrail(id, requester);
    }

    public Trail getOwnedTrail(Long id, User requester) {
        Trail trail = trailRepository.findByIdWithProject(id)
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
