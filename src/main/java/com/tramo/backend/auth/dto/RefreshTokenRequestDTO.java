// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth.dto;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotBlank;

@Getter
@Setter
public class RefreshTokenRequestDTO {

    @NotBlank(message = "TOKEN_REQUIRED")
    private String refreshToken;

}
