package com.mypath.backend.project.entity;

import com.mypath.backend.path.entity.Path;
import com.mypath.backend.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;
import java.util.List;

// user_id+visibility covers the common owner-scoped-by-visibility lookups
// (countByOwnerIdAndVisibility, findByOwnerIdAndVisibilityOrderBy...,
// sumViewCountByOwnerIdAndPublished) and, as a leftmost-prefix, plain
// owner-only lookups too (findByOwnerIdAndForkedFromNotNull*, the self-join
// in findByForkedFromOwnerIdAndOwnerIdNot*). visibility alone covers the
// global published-feed scan (findByVisibilityOrderByModifiedDateDesc, no
// owner filter). forked_from_id covers the fork-direction joins/filters.
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
    // Comma-separated, lowercased tags (e.g. "webdev,react,tutorial") — kept as
    // a flat string rather than a join table since hot-topics counting runs in
    // memory over the (small) set of published projects, not via SQL grouping.
    private String tags;
    private Date creationDate;
    private Date modifiedDate;

    @Column(columnDefinition = "bigint default 0")
    private long viewCount;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User owner;

    // Null for original projects; set to the source project on fork().
    @ManyToOne
    @JoinColumn(name = "forked_from_id")
    private Project forkedFrom;

    @OneToMany(mappedBy = "project")
    private List<Path> paths;
}
