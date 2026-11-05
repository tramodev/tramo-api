// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab.service;

import com.tramo.backend.common.SafeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
public class CollabClient {
    private static final Logger log = LoggerFactory.getLogger(CollabClient.class);

    private final RestClient restClient;
    private final String secret;

    public CollabClient(@Value("${app.collab.sidecar-url:}") String baseUrl, @Value("${app.collab.internal-secret:}") String secret) {
        this.secret = secret;
        if (baseUrl.isBlank() || secret.isBlank()) {
            restClient = null;
            return;
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public void revokeAfterCommit(Long userId) {
        afterCommit("/internal/revoke", Map.of("userId", userId));
    }

    public void resetAfterCommit(Collection<Long> itemIds) {
        if (!itemIds.isEmpty()) afterCommit("/internal/reset", Map.of("itemIds", List.copyOf(itemIds)));
    }

    private void afterCommit(String path, Object body) {
        if (restClient == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(path, body);
                }
            });
            return;
        }
        send(path, body);
    }

    private void send(String path, Object body) {
        try {
            restClient.post().uri(path).header("X-Collab-Secret", secret)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
        } catch (Exception failure) {
            SafeLog.failure(log, "collab_sidecar_unreachable", "COLLAB_UNAVAILABLE", failure);
        }
    }
}
