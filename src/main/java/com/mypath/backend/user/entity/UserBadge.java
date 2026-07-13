package com.mypath.backend.user.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

// Persists the moment a badge (whose earned/progress state is otherwise
// computed live from stats every request — see ProjectService.buildBadges)
// actually gets crossed, so the transition can be detected exactly once and
// trigger a notification instead of re-evaluating "did I earn this already?"
// from nothing every time.
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "badge_code"}),
        indexes = @Index(name = "idx_user_badge_user", columnList = "user_id")
)
public class UserBadge {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "badge_code")
    private String badgeCode;

    private Date earnedAt;
}
