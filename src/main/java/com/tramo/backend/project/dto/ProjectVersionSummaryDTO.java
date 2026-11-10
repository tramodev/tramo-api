package com.tramo.backend.project.dto;

import com.tramo.backend.project.entity.ProjectVersion;
import java.util.Date;
import java.util.List;

public record ProjectVersionSummaryDTO(Long id, Date createdAt, ProjectVersion.Type type, String name, List<Author> authors) {
    public record Author(Long id, String username) {}

    public ProjectVersionSummaryDTO(Long id, Date createdAt, ProjectVersion.Type type, String name) {
        this(id, createdAt, type, name, List.of());
    }

    public ProjectVersionSummaryDTO withAuthors(List<Author> authors) {
        return new ProjectVersionSummaryDTO(id, createdAt, type, name, authors);
    }
}
