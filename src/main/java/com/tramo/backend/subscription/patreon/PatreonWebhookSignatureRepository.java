package com.tramo.backend.subscription.patreon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PatreonWebhookSignatureRepository extends JpaRepository<PatreonWebhookSignature, UUID> {
}
