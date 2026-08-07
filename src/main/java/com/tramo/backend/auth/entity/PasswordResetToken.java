package com.tramo.backend.auth.entity;

import com.tramo.backend.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(indexes = {
        @Index(name = "idx_password_reset_token_user", columnList = "user_id"),
        @Index(name = "idx_password_reset_token_expires", columnList = "expires_at"),
})
public class PasswordResetToken {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String token;

    @ManyToOne(optional = false)
    private User user;

    private Instant expiresAt;
}
