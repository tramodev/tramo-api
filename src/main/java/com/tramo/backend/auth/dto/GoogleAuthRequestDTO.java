// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GoogleAuthRequestDTO {

    @NotBlank(message = "ID_TOKEN_REQUIRED")
    private String idToken;

    @Size(max = 40, message = "PASSWORD_SIZE_INVALID")
    private String password;
}
