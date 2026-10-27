package com.tramo.backend.project;

import com.tramo.backend.project.snapshot.SnapshotLimits;
import com.tramo.backend.project.snapshot.SnapshotProcessing;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.PreparedStatementCallback;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Properties;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SnapshotProcessingTest {
    @Test
    void configuredBudgetFetchesMultipleRowsPerRoundTrip() throws Exception {
        var properties = new Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) {
            properties.load(input);
        }
        var limits = new SnapshotLimits(Integer.parseInt(properties.getProperty("app.snapshot.max-bytes")),
                1, 1, 1, new ObjectMapper());
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var statement = mock(PreparedStatement.class);
        when(statement.executeQuery()).thenReturn(mock(ResultSet.class));
        doAnswer(invocation -> {
            PreparedStatementCallback<?> callback = invocation.getArgument(2);
            return callback.doInPreparedStatement(statement);
        }).when(jdbc).execute(eq("SELECT content"), eq(Map.of()), any(PreparedStatementCallback.class));
        var processing = new SnapshotProcessing(jdbc, limits, mock(PlatformTransactionManager.class),
                Integer.parseInt(properties.getProperty("app.snapshot.processing-bytes")));
        processing.read("SELECT content", Map.of(), rows -> {});
        verify(statement).setFetchSize(4);
        verify(statement).executeQuery();
    }

    @Test
    void processingBudgetMustFitOneValidSnapshot() {
        var limits = new SnapshotLimits(4096, 1, 1, 1, new ObjectMapper());
        assertThatThrownBy(() -> new SnapshotProcessing(mock(NamedParameterJdbcTemplate.class), limits,
                mock(PlatformTransactionManager.class), 4095))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must fit one maximum-size snapshot");
    }
}
