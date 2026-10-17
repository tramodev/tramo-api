package com.tramo.backend.project.service;

import com.tramo.backend.project.dto.GraphPreviewDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.snapshot.ProjectSnapshotData;
import com.tramo.backend.project.snapshot.SnapshotCapture;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProjectThumbnailResolver {
    private final SnapshotCapture capture;

    public ProjectThumbnailResolver(SnapshotCapture capture) {
        this.capture = capture;
    }

    ThumbnailResolution resolveThumbnail(Project project) {
        return resolveThumbnails(List.of(project)).getOrDefault(project.getId(), ThumbnailResolution.EMPTY);
    }

    Map<Long, ThumbnailResolution> resolveThumbnails(List<Project> projects) {
        Map<Long, ThumbnailResolution> result = new HashMap<>();
        capture.capturePreviews(projects.stream().map(Project::getId).toList(),
                (id, data) -> result.put(id, resolvePublished(data)));
        return result;
    }

    ThumbnailResolution resolvePublished(ProjectSnapshotData data) {
        String type = data.thumbnailType();
        if ("DEDICATED".equals(type) || "PROJECT_IMAGE".equals(type)
                || (type == null && data.thumbnail() != null)) {
            return new ThumbnailResolution(data.thumbnail(), null);
        }
        if ("GRAPH".equals(type)) return new ThumbnailResolution(null, graph(data.trails()));
        for (var trail : data.trails()) {
            GraphPreviewDTO graph = graph(List.of(trail));
            if (graph != null && graph.items().stream().anyMatch(i -> !i.linkedItemIds().isEmpty())) {
                return new ThumbnailResolution(null, graph);
            }
        }
        return ThumbnailResolution.EMPTY;
    }

    private GraphPreviewDTO graph(List<ProjectSnapshotData.TrailData> trails) {
        if (trails.isEmpty()) return null;
        Map<Long, ProjectSnapshotData.ItemData> items = new LinkedHashMap<>();
        trails.forEach(t -> t.items().forEach(i -> items.putIfAbsent(i.id(), i)));
        List<GraphPreviewDTO.GraphTrailDTO> previewTrails = trails.stream().map(t ->
                new GraphPreviewDTO.GraphTrailDTO(String.valueOf(t.id()), t.title(),
                        t.items().stream().map(i -> String.valueOf(i.id())).toList())).toList();
        List<GraphPreviewDTO.GraphItemDTO> previewItems = items.values().stream().map(i ->
                new GraphPreviewDTO.GraphItemDTO(String.valueOf(i.id()), i.title(),
                        GraphLookup.linkedItemIds(i.content()).stream()
                                .filter(id -> !id.equals(i.id()) && items.containsKey(id))
                                .map(String::valueOf).toList())).toList();
        return new GraphPreviewDTO(previewTrails, previewItems);
    }
}
