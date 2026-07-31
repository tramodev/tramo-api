package com.tramo.backend.project.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SetThumbnailRequestDTO {
    @Pattern(regexp = "NONE|GRAPH|PROJECT_IMAGE|DEDICATED", message = "Invalid thumbnail type")
    private String type;

    // Required when type = GRAPH
    private String trailId;

    // Required when type = PROJECT_IMAGE or DEDICATED
    private String imageUrl;
}
