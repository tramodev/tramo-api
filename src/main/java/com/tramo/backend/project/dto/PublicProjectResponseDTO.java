package com.tramo.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class PublicProjectResponseDTO {
    private String id;
    private String title;
    private String description;
    private String ownerUsername;
    private Date modifiedDate;
    private String thumbnailImageUrl;
    private List<PublicTrailDTO> trails;
    private long voteCount;
    private boolean votedByRequester;
    private boolean bookmarkedByRequester;
    private long viewCount;
    private long commentCount;
    private String visibility;
    private String forkedFromProjectId;
    private String forkedFromTitle;
    private String forkedFromOwnerUsername;
    private boolean canFork;
    private boolean canComment;
}
