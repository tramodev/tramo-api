package com.tramo.backend.user.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BlockedUserDTO {
    private String username;
    private String imageUrl;
    private String bio;
}
