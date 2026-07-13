package com.mypath.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class ProfileStatsDTO {
    private long pathsPublished;
    private long upvotesReceived;
    private long totalViews;
    private long forksCount;
}
