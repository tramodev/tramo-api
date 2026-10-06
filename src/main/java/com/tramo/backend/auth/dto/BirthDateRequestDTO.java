package com.tramo.backend.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class BirthDateRequestDTO {

    @NotNull(message = "BIRTH_DATE_REQUIRED")
    @Past(message = "BIRTH_DATE_INVALID")
    private LocalDate birthDate;
}
