package com.mypath.backend.project.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

// Deduplicates the public view counter per visitor: `viewerKey` is either
// "user:<id>" (logged-in) or "anon:<cookie-uuid>" (anonymous, minted by
// middleware.ts on first visit to a /p/* page) — a single non-null string
// column keeps the unique constraint simple instead of two nullable FK-style
// columns that would each need their own partial unique index.
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "viewer_key"}))
public class ProjectView {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(name = "viewer_key")
    private String viewerKey;

    private Date createdDate;
}
