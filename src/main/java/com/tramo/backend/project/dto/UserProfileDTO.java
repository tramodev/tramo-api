// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class UserProfileDTO {
    private String username;
    private String email;
    private String bio;
    private LocalDate birthDate;
    private String location;
    private String website;
    private String imageUrl;
    private String bannerUrl;
    private Date createdAt;
    private String role;
    private String selectedBadge;
}
