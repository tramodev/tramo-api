package com.mypath.backend.moderation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class ReportDTO {
    private Long id;
    private Long projectId;
    private String projectTitle;
    private String reporterUsername;
    private String reason;
    private String status;
    private Date createdDate;
}
