package com.tramo.backend.project.dto;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class ProjectFeedItemDTO {
    private String id;
    private String title;
    private String description;
    private String ownerUsername;
    private String ownerAvatar;
    private String ownerBadge;
    private String thumbnailImageUrl;
    private GraphPreviewDTO thumbnailGraph;
    private List<String> tags;
    private Date modifiedDate;
    private Date publishedDate;
    private Date lastPublishedDate;
    private long voteCount;
    private boolean votedByRequester;
    private boolean bookmarkedByRequester;
    private long viewCount;
    private long forkCount;
    private long commentCount;
    private boolean featured;
    private String forkedFromProjectId;
    private String forkedFromTitle;
    private String forkedFromOwnerUsername;
}
