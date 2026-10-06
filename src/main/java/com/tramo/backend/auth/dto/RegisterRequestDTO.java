package com.tramo.backend.auth.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class RegisterRequestDTO {

    @NotBlank(message = "USERNAME_REQUIRED")
    @Size(min = 3, max = 20, message = "USERNAME_SIZE_INVALID")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "USERNAME_FORMAT_INVALID")
    private String username;

    @NotBlank(message = "PASSWORD_REQUIRED")
    @Size(min = 12, max = 40, message = "PASSWORD_SIZE_INVALID")
    @Pattern(
            regexp = "^(?=.*[A-Z])(?=.*\\d)(?=.*[^a-zA-Z0-9]).+$",
            message = "PASSWORD_FORMAT_INVALID"
    )
    private String password;

    @NotBlank(message = "EMAIL_REQUIRED")
    @Email(message = "EMAIL_INVALID")
    private String email;

    @NotBlank(message = "CAPTCHA_REQUIRED")
    private String captchaToken;

    @NotNull(message = "BIRTH_DATE_REQUIRED")
    @Past(message = "BIRTH_DATE_INVALID")
    private LocalDate birthDate;

    private Boolean visibility;

    public RegisterRequestDTO() {
        this.visibility = true;
    }
}