package com.tramo.backend.project.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Entity
@Getter
@Setter
public class ProjectVersion {
    public enum Type { AUTO, MANUAL, RESTORE }
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String snapshot;
    @Column(nullable = false)
    private Date createdAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;
    @Column(length = 120)
    private String name;
    @Column(nullable = false, length = 64)
    private String stateHash;
    @Column(nullable = false)
    private long snapshotBytes;
}
