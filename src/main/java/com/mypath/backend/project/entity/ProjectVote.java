package com.mypath.backend.project.entity;

import com.mypath.backend.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

@Entity
@Getter
@Setter
@NoArgsConstructor
// The (project_id, user_id) unique constraint already indexes project_id-led
// lookups (countByProjectId, findByProjectIdAndUserId, the grouped vote-count
// batch); user_id-led lookups (findByUserId*, the anti-join in
// findByProjectOwnerIdAndUserIdNot*) need their own index since a composite
// index can't serve a query filtering only on its non-leading column.
@Table(
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "user_id"}),
        indexes = @Index(name = "idx_project_vote_user", columnList = "user_id")
)
public class ProjectVote {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    private Date createdDate;
}
