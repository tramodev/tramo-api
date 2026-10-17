package com.tramo.backend.project.dto;

import com.tramo.backend.project.entity.ProjectVersion;
import java.util.Date;

public record ProjectVersionSummaryDTO(Long id, Date createdAt, ProjectVersion.Type type, String name) {}
