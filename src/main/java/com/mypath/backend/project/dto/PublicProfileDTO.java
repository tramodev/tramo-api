package com.mypath.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class PublicProfileDTO {
    private String username;
    private String bio;
    private String imageUrl;
    private Date createdAt;
    private ProfileStatsDTO stats;
    private List<BadgeDTO> badges;
    private boolean following;
    private boolean self;
}
