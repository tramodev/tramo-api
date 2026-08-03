package com.tramo.backend.subscription.patreon;

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
public class PatreonWebhookSignature {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String signature;

    private Instant processedAt;

    public PatreonWebhookSignature(String signature) {
        this.signature = signature;
        this.processedAt = Instant.now();
    }
}
