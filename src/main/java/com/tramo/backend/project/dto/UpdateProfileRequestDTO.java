package com.tramo.backend.project.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class UpdateProfileRequestDTO {
    @Size(max = 500, message = "Bio must be at most 500 characters")
    private String bio;
    private LocalDate birthDate;
    @Size(max = 100, message = "Location must be at most 100 characters")
    private String location;
    @Size(max = 200, message = "Website must be at most 200 characters")
    private String website;
    private String imageUrl;
    private String bannerUrl;
    private String selectedBadge;
}
