package com.mypath.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class ProfileBundleDTO {
    private ProfileStatsDTO stats;
    private List<BadgeDTO> badges;
    private List<ProjectFeedItemDTO> bookmarks;
    private List<ProjectFeedItemDTO> upvoted;
    private List<ForkFeedItemDTO> forks;
    private List<ProjectFeedItemDTO> published;
    private List<ActivityItemDTO> activity;
}
