package com.mypath.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class UserProfileDTO {
    private String username;
    private String email;
    private String bio;
    private String imageUrl;
    private Date createdAt;
    private String role;
}
