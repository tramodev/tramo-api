package com.tramo.backend.project.dto;

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
    private Integer age;
    private String location;
    private String website;
    private String imageUrl;
    private String bannerUrl;
    private Date createdAt;
    private ProfileStatsDTO stats;
    private List<BadgeDTO> badges;
    private String selectedBadge;
    private boolean following;
    private boolean self;
    private boolean blocked;
    private boolean showUpvotes;
}
