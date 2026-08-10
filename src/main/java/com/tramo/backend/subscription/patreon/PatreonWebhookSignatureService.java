package com.tramo.backend.subscription.patreon;

import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class PatreonWebhookSignatureService {
    private static final int WEBHOOK_SIGNATURE_RETENTION_DAYS = 30;
    private static final Logger log = LoggerFactory.getLogger(PatreonWebhookSignatureService.class);

    private final PatreonWebhookSignatureRepository patreonWebhookSignatureRepository;

    public PatreonWebhookSignatureService(PatreonWebhookSignatureRepository patreonWebhookSignatureRepository) {
        this.patreonWebhookSignatureRepository = patreonWebhookSignatureRepository;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeProcessedSignatures() {
        long deletedWebhookSignatures = patreonWebhookSignatureRepository
                .deleteByProcessedAtBefore(Instant.now().minus(WEBHOOK_SIGNATURE_RETENTION_DAYS, ChronoUnit.DAYS));
        if (deletedWebhookSignatures > 0) {
            log.info("purgeProcessedSignatures deleted {} processed Patreon webhook signatures", deletedWebhookSignatures);
        }
    }
}
