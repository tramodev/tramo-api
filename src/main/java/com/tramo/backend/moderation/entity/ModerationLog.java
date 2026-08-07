package com.tramo.backend.moderation.entity;

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
@Table(indexes = @Index(name = "idx_moderation_log_admin", columnList = "admin_id"))
public class ModerationLog {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "admin_id")
    private User admin;

    private String action;

    private String targetType;

    private Long targetId;

    private String reason;

    private Date createdDate;
}
