package com.tramo.backend.project.entity;

import com.tramo.backend.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

@Entity
@Getter
@Setter
@NoArgsConstructor
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

    private String voterIp;

    private String deviceId;
}
