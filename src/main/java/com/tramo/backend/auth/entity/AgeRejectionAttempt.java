package com.tramo.backend.auth.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "age_rejection_attempts",
        indexes = @Index(name = "idx_age_rejection_attempts_ip_rejected_at", columnList = "ip_address, rejected_at"))
public class AgeRejectionAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ip_address", nullable = false)
    private String ipAddress;

    @Column(name = "rejected_at", nullable = false)
    private Instant rejectedAt;
}
