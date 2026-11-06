// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.security;

import com.tramo.backend.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMetrics
class MetricsEndpointTest extends AbstractIntegrationTest {

    @Test
    void prometheusMetricsRequireTheMetricsSecret() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/actuator/prometheus").header("X-Metrics-Secret", "wrong")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/actuator/env").header("X-Metrics-Secret", TEST_METRICS_SECRET)).andExpect(status().is4xxClientError());
        assertThat(mockMvc.perform(get("/actuator/prometheus").header("X-Metrics-Secret", TEST_METRICS_SECRET))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains("jvm_memory");
    }
}
