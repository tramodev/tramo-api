package com.tramo.backend.subscription.patreon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.UUID;

public interface PatreonWebhookSignatureRepository extends JpaRepository<PatreonWebhookSignature, UUID> {

    @Modifying(flushAutomatically = true)
    @Query("delete from PatreonWebhookSignature s where s.processedAt < :cutoff")
    int deleteByProcessedAtBefore(@Param("cutoff") Instant cutoff);
}
