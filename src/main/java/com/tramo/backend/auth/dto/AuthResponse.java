package com.tramo.backend.auth.dto;

import lombok.Getter;
import lombok.Setter;

@Getter@Setter
public class AuthResponse {
    String accessToken;
    String refreshToken;
    String username;
    boolean requiresBirthDate;

    public AuthResponse(String accessToken, String refreshToken, String username, boolean requiresBirthDate) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.username = username;
        this.requiresBirthDate = requiresBirthDate;
    }

}
