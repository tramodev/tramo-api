// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.dto;

import com.tramo.backend.project.entity.ProjectVisibility;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ProjectRequestDTO {
    private String title;
    private String description;
    private ProjectVisibility visibility;
    private List<String> tags;
    @Size(max = 65536)
    private String graphColors;
}
