package com.mypath.backend.project.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProjectRequestDTO {
    private String title;
    private String description;
    private String visibility;
}
