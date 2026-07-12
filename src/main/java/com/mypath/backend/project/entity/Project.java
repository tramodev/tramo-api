package com.mypath.backend.project.entity;

import com.mypath.backend.path.entity.Path;
import com.mypath.backend.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private String title;
    private String description;
    private String visibility;
    @Column(columnDefinition = "TEXT")
    private String thumbnail;
    // Comma-separated, lowercased tags (e.g. "webdev,react,tutorial") — kept as
    // a flat string rather than a join table since hot-topics counting runs in
    // memory over the (small) set of published projects, not via SQL grouping.
    private String tags;
    private Date creationDate;
    private Date modifiedDate;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User owner;

    @OneToMany(mappedBy = "project")
    private List<Path> paths;
}
