// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePasswordRequestDTO {

    @NotBlank(message = "CURRENT_PASSWORD_REQUIRED")
    private String currentPassword;

    @NotBlank(message = "NEW_PASSWORD_REQUIRED")
    @Size(min = 12, max = 40, message = "PASSWORD_SIZE_INVALID")
    @Pattern(
            regexp = "^(?=.*[A-Z])(?=.*\\d)(?=.*[^a-zA-Z0-9]).+$",
            message = "PASSWORD_FORMAT_INVALID"
    )
    private String newPassword;
}
