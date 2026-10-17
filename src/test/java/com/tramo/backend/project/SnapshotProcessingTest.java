package com.tramo.backend.project;

import com.tramo.backend.project.snapshot.SnapshotLimits;
import com.tramo.backend.project.snapshot.SnapshotProcessing;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SnapshotProcessingTest {
    @Test
    void processingBudgetMustFitOneValidSnapshot() {
        var limits = new SnapshotLimits(4096, 1, 1, 1, new ObjectMapper());
        assertThatThrownBy(() -> new SnapshotProcessing(mock(NamedParameterJdbcTemplate.class), limits,
                mock(PlatformTransactionManager.class), 4095))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must fit one maximum-size snapshot");
    }
}
