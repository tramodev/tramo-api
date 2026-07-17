package com.mypath.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class ProjectResponseDTO {
    private String id;
    private String title;
    private String description;
    private String visibility;
    private String thumbnail;
    private String tags;
    private Date creationDate;
    private Date modifiedDate;
}
