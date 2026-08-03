package com.tramo.backend.project.dto;

import com.tramo.backend.project.entity.ProjectVisibility;
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
}
