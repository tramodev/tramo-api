package com.mypath.backend.notification.entity;

import com.mypath.backend.project.entity.Project;
import com.mypath.backend.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

// One row per "kind of thing that happened to you", aggregated while unread —
// see NotificationService.recordEvent for the upsert-on-unread logic that
// keeps a viral post's 200 upvotes from becoming 200 rows.
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(indexes = {
        @Index(name = "idx_notification_recipient", columnList = "recipient_id"),
        @Index(name = "idx_notification_recipient_unread", columnList = "recipient_id, read"),
})
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "recipient_id")
    private User recipient;

    // UPVOTE | FORK | FOLLOW | BADGE | FEATURED
    private String type;

    // Set for UPVOTE/FORK/FEATURED; null for FOLLOW/BADGE.
    @ManyToOne
    @JoinColumn(name = "project_id")
    private Project project;

    // Set for BADGE only — denormalized display name captured at award time,
    // so rendering a notification never needs a separate badge-catalog lookup.
    private String badgeCode;
    private String badgeName;

    // Most recent actor for UPVOTE/FORK/FOLLOW; null for BADGE/FEATURED
    // (nobody "did" those to you, they're your own achievement/project).
    @ManyToOne
    @JoinColumn(name = "actor_id")
    private User latestActor;

    private int count = 1;

    private boolean read = false;

    private Date createdDate;
    private Date updatedDate;
}
