package com.mypath.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class ActivityItemDTO {
    private String type;
    private Date timestamp;
    private String projectId;
    private String projectTitle;
    private String otherUsername;
}
