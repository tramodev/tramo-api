package com.tramo.backend.project.entity;

import com.tramo.backend.path.entity.Path;
import com.tramo.backend.user.entity.User;
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
@Table(indexes = {
        @Index(name = "idx_project_owner_visibility", columnList = "user_id, visibility"),
        @Index(name = "idx_project_visibility", columnList = "visibility"),
        @Index(name = "idx_project_forked_from", columnList = "forked_from_id"),
})
public class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private String title;
    private String description;
    private String visibility;
    @Column(columnDefinition = "TEXT")
    private String thumbnail;
    private String tags;
    private Date creationDate;
    private Date modifiedDate;
    private Date lastEditedDate;

    @Column(columnDefinition = "bigint default 0")
    private long viewCount;

    @Column(columnDefinition = "boolean default false")
    private boolean featured;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User owner;

    @ManyToOne
    @JoinColumn(name = "forked_from_id")
    private Project forkedFrom;

    @OneToMany(mappedBy = "project")
    private List<Path> paths;
}
