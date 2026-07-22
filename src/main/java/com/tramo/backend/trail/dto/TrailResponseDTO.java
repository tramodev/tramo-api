package com.tramo.backend.trail.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class TrailResponseDTO {
    private Long id;
    private String title;
    private String visibility;
    private Date creationDate;
    private Date modifiedDate;
    private String projectId;
    private int version;
    private Long forkedFromId;
}
