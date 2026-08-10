package com.tramo.backend.project.service;

import com.tramo.backend.project.dto.GraphPreviewDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectThumbnailType;
import com.tramo.backend.trail.entity.ItemImageReference;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.repository.AssociationRepository;
import com.tramo.backend.trail.repository.ItemImageReferenceRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ProjectThumbnailResolver {
    private final TrailRepository trailRepository;
    private final TrailItemRepository trailItemRepository;
    private final AssociationRepository itemLinkRepository;
    private final ItemImageReferenceRepository itemImageReferenceRepository;

    public ProjectThumbnailResolver(TrailRepository trailRepository, TrailItemRepository trailItemRepository,
                                     AssociationRepository itemLinkRepository,
                                     ItemImageReferenceRepository itemImageReferenceRepository) {
        this.trailRepository = trailRepository;
        this.trailItemRepository = trailItemRepository;
        this.itemLinkRepository = itemLinkRepository;
        this.itemImageReferenceRepository = itemImageReferenceRepository;
    }

    ThumbnailResolution resolveThumbnail(Project project) {
        return resolveThumbnails(List.of(project)).getOrDefault(project.getId(), ThumbnailResolution.EMPTY);
    }

    Map<Long, ThumbnailResolution> resolveThumbnails(List<Project> projects) {
        Map<Long, ThumbnailResolution> result = new HashMap<>();
        List<Project> chosenGraphProjects = new ArrayList<>();
        List<Project> fallbackCandidates = new ArrayList<>();

        for (Project project : projects) {
            ProjectThumbnailType type = project.getThumbnailType();
            if (type == null || type == ProjectThumbnailType.NONE) {
                fallbackCandidates.add(project);
            } else if (type == ProjectThumbnailType.GRAPH) {
                if (project.getThumbnailTrail() != null) {
                    chosenGraphProjects.add(project);
                } else {
                    result.put(project.getId(), ThumbnailResolution.EMPTY);
                }
            } else {
                result.put(project.getId(), new ThumbnailResolution(project.getThumbnailImageUrl(), null));
            }
        }

        if (!chosenGraphProjects.isEmpty()) {
            List<Long> trailIds = chosenGraphProjects.stream().map(p -> p.getThumbnailTrail().getId()).toList();
            GraphLookup lookup = GraphLookup.forTrailIds(trailIds, trailItemRepository, itemLinkRepository);
            for (Project project : chosenGraphProjects) {
                Trail trail = project.getThumbnailTrail();
                result.put(project.getId(), new ThumbnailResolution(null, lookup.buildGraphPreview(trail)));
            }
        }

        if (!fallbackCandidates.isEmpty()) {
            List<Long> fallbackProjectIds = fallbackCandidates.stream().map(Project::getId).toList();
            Map<Long, List<Trail>> trailsByProjectId = trailRepository.findByProjectIdIn(fallbackProjectIds).stream()
                    .collect(Collectors.groupingBy(t -> t.getProject().getId(), LinkedHashMap::new, Collectors.toList()));
            List<Long> allTrailIds = trailsByProjectId.values().stream().flatMap(List::stream).map(Trail::getId).toList();
            GraphLookup lookup = GraphLookup.forTrailIds(allTrailIds, trailItemRepository, itemLinkRepository);

            List<Long> needsImageFallback = new ArrayList<>();
            for (Project project : fallbackCandidates) {
                GraphPreviewDTO chosen = null;
                for (Trail trail : trailsByProjectId.getOrDefault(project.getId(), List.of())) {
                    GraphPreviewDTO graph = lookup.buildGraphPreview(trail);
                    if (graph != null && graph.items().stream().anyMatch(i -> !i.associations().isEmpty())) {
                        chosen = graph;
                        break;
                    }
                }
                if (chosen != null) {
                    result.put(project.getId(), new ThumbnailResolution(null, chosen));
                } else {
                    needsImageFallback.add(project.getId());
                }
            }

            if (!needsImageFallback.isEmpty()) {
                Map<Long, ItemImageReference> firstImageByProjectId = new LinkedHashMap<>();
                for (ItemImageReference ref : itemImageReferenceRepository.findByProjectIdInOrderByItemIdAsc(needsImageFallback)) {
                    firstImageByProjectId.putIfAbsent(ref.getItem().getProject().getId(), ref);
                }
                for (Long projectId : needsImageFallback) {
                    ItemImageReference ref = firstImageByProjectId.get(projectId);
                    result.put(projectId, ref != null ? new ThumbnailResolution(ref.getUrl(), null) : ThumbnailResolution.EMPTY);
                }
            }
        }

        return result;
    }
}
