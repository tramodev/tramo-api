package com.mypath.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class FollowUserDTO {
    private String username;
    private String imageUrl;
    private String bio;
    private boolean followingByRequester;
}
