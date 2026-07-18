package com.tramo.backend.notification.entity;

import com.tramo.backend.project.entity.Project;
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

    private String type;

    @ManyToOne
    @JoinColumn(name = "project_id")
    private Project project;

    private String badgeCode;
    private String badgeName;

    @ManyToOne
    @JoinColumn(name = "actor_id")
    private User latestActor;

    private int count = 1;

    private boolean read = false;

    private Date createdDate;
    private Date updatedDate;
}
