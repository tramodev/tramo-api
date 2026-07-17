package com.mypath.backend.path.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class PathResponseDTO {
    private Long id;
    private String title;
    private String visibility;
    private Date creationDate;
    private Date modifiedDate;
    private String projectId;
}
