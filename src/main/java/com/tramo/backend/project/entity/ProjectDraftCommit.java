package com.tramo.backend.project.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;
import java.util.Date;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class ProjectDraftCommit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private UUID clientRequestId;
    private Long projectId;
    private Long trailId;
    private Long itemId;
    private String projectTitle;
    private String trailTitle;
    private String itemTitle;
    private Date createdAt = new Date();
}
