package com.tramo.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GoogleAuthRequestDTO {

    @NotBlank(message = "ID token is required")
    private String idToken;
}
