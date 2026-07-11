package com.mypath.backend.auth.dto;

import lombok.Getter;
import lombok.Setter;

@Getter@Setter
public class AuthResponse {
    String accessToken;
    String refreshToken;
    String username;

    public AuthResponse(String accessToken, String refreshToken, String username) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.username = username;
    }

}
