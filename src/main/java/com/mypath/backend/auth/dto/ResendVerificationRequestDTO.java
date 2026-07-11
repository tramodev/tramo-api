package com.mypath.backend.auth.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResendVerificationRequestDTO {
    private String username;
    private String email;
}
