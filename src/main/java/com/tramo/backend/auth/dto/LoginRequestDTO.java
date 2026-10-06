package com.tramo.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class LoginRequestDTO {

    @NotBlank(message = "USERNAME_REQUIRED")
    private String username;

    @NotBlank(message = "PASSWORD_REQUIRED")
    private String password;
}
