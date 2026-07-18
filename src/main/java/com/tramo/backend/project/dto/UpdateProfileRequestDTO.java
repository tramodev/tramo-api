package com.tramo.backend.project.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateProfileRequestDTO {
    private String bio;
    private String imageUrl;
}
