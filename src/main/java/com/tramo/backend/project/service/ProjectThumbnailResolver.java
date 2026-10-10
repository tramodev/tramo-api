// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.project.dto.GraphPreviewDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectThumbnailType;
import com.tramo.backend.trail.entity.Trail;
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

    public ProjectThumbnailResolver(TrailRepository trailRepository, TrailItemRepository trailItemRepository) {
        this.trailRepository = trailRepository;
        this.trailItemRepository = trailItemRepository;
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
                chosenGraphProjects.add(project);
            } else {
                result.put(project.getId(), new ThumbnailResolution(project.getThumbnailImageUrl(), null));
            }
        }

        if (!chosenGraphProjects.isEmpty() || !fallbackCandidates.isEmpty()) {
            List<Project> graphProjects = new ArrayList<>(chosenGraphProjects);
            graphProjects.addAll(fallbackCandidates);
            List<Long> graphProjectIds = graphProjects.stream().map(Project::getId).toList();
            Map<Long, List<Trail>> trailsByProjectId = trailRepository.findByProjectIdIn(graphProjectIds).stream()
                    .collect(Collectors.groupingBy(t -> t.getProject().getId(), LinkedHashMap::new, Collectors.toList()));
            List<Long> allTrailIds = trailsByProjectId.values().stream().flatMap(List::stream).map(Trail::getId).toList();
            GraphLookup lookup = GraphLookup.forTrailIds(allTrailIds, trailItemRepository);

            for (Project project : chosenGraphProjects) {
                result.put(project.getId(), new ThumbnailResolution(null,
                        lookup.buildGraphPreview(trailsByProjectId.getOrDefault(project.getId(), List.of()))));
            }

            List<Long> needsImageFallback = new ArrayList<>();
            for (Project project : fallbackCandidates) {
                GraphPreviewDTO chosen = null;
                for (Trail trail : trailsByProjectId.getOrDefault(project.getId(), List.of())) {
                    GraphPreviewDTO graph = lookup.buildGraphPreview(trail);
                    if (graph != null && graph.items().stream().anyMatch(i -> !i.linkedItemIds().isEmpty())) {
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

            for (Long projectId : needsImageFallback) result.put(projectId, ThumbnailResolution.EMPTY);
        }

        return result;
    }
}
