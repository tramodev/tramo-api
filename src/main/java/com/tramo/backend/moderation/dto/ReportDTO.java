package com.tramo.backend.moderation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class ReportDTO {
    private Long id;
    private String type;
    private String projectId;
    private String projectTitle;
    private Long commentId;
    private String commentContent;
    private String reporterUsername;
    private String reason;
    private String status;
    private Date createdDate;
}
