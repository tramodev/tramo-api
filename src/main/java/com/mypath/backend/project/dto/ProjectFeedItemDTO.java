package com.mypath.backend.project.dto;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class ProjectFeedItemDTO {
    private Long id;
    private String title;
    private String description;
    private String ownerUsername;
    private String thumbnail;
    private String tags;
    private Date modifiedDate;
    private long voteCount;
    private boolean votedByRequester;
    private boolean bookmarkedByRequester;
    private long viewCount;
}
