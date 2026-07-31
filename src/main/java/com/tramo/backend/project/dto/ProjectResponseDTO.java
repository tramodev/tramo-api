package com.tramo.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class ProjectResponseDTO {
    private String id;
    private String title;
    private String description;
    private String visibility;
    private String thumbnailImageUrl;
    private GraphPreviewDTO thumbnailGraph;
    private List<String> tags;
    private Date creationDate;
    private Date modifiedDate;
    private long storageBytes;
}
