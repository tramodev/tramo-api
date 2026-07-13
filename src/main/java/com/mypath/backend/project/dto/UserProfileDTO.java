package com.mypath.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class UserProfileDTO {
    private String username;
    private String bio;
    private String imageUrl;
}
