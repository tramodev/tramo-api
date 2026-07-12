package com.mypath.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class PublicProjectResponseDTO {
    private Long id;
    private String title;
    private String description;
    private String ownerUsername;
    private Date modifiedDate;
    private List<PublicPathDTO> paths;
    private long voteCount;
    private boolean votedByRequester;
}
