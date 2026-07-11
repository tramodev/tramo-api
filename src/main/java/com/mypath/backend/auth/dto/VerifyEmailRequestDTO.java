package com.mypath.backend.auth.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VerifyEmailRequestDTO {
    private String token;
}
