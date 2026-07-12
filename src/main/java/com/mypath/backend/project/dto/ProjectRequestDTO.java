package com.mypath.backend.project.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProjectRequestDTO {
    private String title;
    private String description;

    @Pattern(regexp = "private|unlisted|published", message = "Visibility must be private, unlisted, or published")
    private String visibility;

    private String thumbnail;
    private String tags;
}
