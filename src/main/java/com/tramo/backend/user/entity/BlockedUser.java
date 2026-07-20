package com.tramo.backend.user.entity;

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
        uniqueConstraints = @UniqueConstraint(columnNames = {"blocker_id", "blocked_id"}),
        indexes = @Index(name = "idx_blocked_user_blocked", columnList = "blocked_id")
)
public class BlockedUser {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "blocker_id")
    private User blocker;

    @ManyToOne
    @JoinColumn(name = "blocked_id")
    private User blocked;

    private Date createdDate;
}
