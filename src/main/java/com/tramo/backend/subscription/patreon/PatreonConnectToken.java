package com.tramo.backend.subscription.patreon;

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
@Table(indexes = @Index(name = "idx_patreon_connect_token_user", columnList = "user_id"))
public class PatreonConnectToken {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String token;

    @ManyToOne(optional = false)
    private User user;

    private Instant expiresAt;
}
