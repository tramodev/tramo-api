package com.mypath.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class ProjectResponseDTO {
    private Long id;
    private String title;
    private String description;
    private String visibility;
    private Date creationDate;
    private Date modifiedDate;
}
