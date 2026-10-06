// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class UpdateProfileRequestDTO {
    @Size(max = 500, message = "BIO_SIZE_INVALID")
    private String bio;
    private LocalDate birthDate;
    @Size(max = 100, message = "LOCATION_SIZE_INVALID")
    private String location;
    @Size(max = 200, message = "WEBSITE_SIZE_INVALID")
    private String website;
    private String imageUrl;
    private String bannerUrl;
    private String selectedBadge;
}
