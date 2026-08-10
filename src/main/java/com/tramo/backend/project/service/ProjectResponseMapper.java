package com.tramo.backend.project.service;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.project.dto.ProjectResponseDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.tag.entity.Tag;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class ProjectResponseMapper {
    private final ProjectRepository projectRepository;
    private final ItemRepository itemRepository;
    private final UploadRecordRepository uploadRecordRepository;
    private final ProjectIdCodec projectIdCodec;
    private final ProjectThumbnailResolver thumbnailResolver;

    public ProjectResponseMapper(ProjectRepository projectRepository, ItemRepository itemRepository,
                                  UploadRecordRepository uploadRecordRepository, ProjectIdCodec projectIdCodec,
                                  ProjectThumbnailResolver thumbnailResolver) {
        this.projectRepository = projectRepository;
        this.itemRepository = itemRepository;
        this.uploadRecordRepository = uploadRecordRepository;
        this.projectIdCodec = projectIdCodec;
        this.thumbnailResolver = thumbnailResolver;
    }

    ProjectResponseDTO toResponse(Project project) {
        return toResponse(project, resolveTagNames(project));
    }

    ProjectResponseDTO toResponse(Project project, List<String> tagNames) {
        long bytes = uploadRecordRepository.sumBytesByProjectId(project.getId())
                + itemRepository.sumContentBytesByProjectId(project.getId());
        return toResponse(project, bytes, tagNames);
    }

    ProjectResponseDTO toResponse(Project project, long storageBytes, List<String> tagNames) {
        return toResponse(project, storageBytes, tagNames, thumbnailResolver.resolveThumbnail(project));
    }

    ProjectResponseDTO toResponse(Project project, long storageBytes, List<String> tagNames, ThumbnailResolution thumbnail) {
        Project source = project.getForkedFrom();
        return new ProjectResponseDTO(
                projectIdCodec.encode(project.getId()),
                project.getTitle(),
                project.getDescription(),
                project.getVisibility(),
                thumbnail.imageUrl(),
                thumbnail.graph(),
                tagNames,
                project.getCreationDate(),
                latestOf(project.getModifiedDate(), project.getLastEditedDate()),
                storageBytes,
                source != null ? projectIdCodec.encode(source.getId()) : null,
                source != null ? source.getTitle() : null,
                source != null ? source.getOwner().getUsername() : null
        );
    }

    List<String> liveTagNames(Project project) {
        return project.getProjectTags().stream().map(Tag::getName).sorted().toList();
    }

    Map<Long, List<String>> tagNamesGroupedByProjectId(List<Long> projectIds) {
        Map<Long, List<String>> byProjectId = new HashMap<>();
        for (ProjectRepository.ProjectTagName row : projectRepository.findTagNamesGroupedByProjectIdIn(projectIds)) {
            byProjectId.computeIfAbsent(row.getProjectId(), k -> new ArrayList<>()).add(row.getTagName());
        }
        return byProjectId;
    }

    List<String> resolveTagNames(Project project) {
        return tagNamesGroupedByProjectId(List.of(project.getId())).getOrDefault(project.getId(), List.of());
    }

    List<String> normalizeTagNames(List<String> rawNames) {
        if (rawNames == null) return List.of();
        return rawNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
    }

    private Date latestOf(Date a, Date b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.after(b) ? a : b;
    }
}
