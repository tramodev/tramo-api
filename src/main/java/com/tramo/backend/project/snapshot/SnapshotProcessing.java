package com.tramo.backend.project.snapshot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

@Component
public class SnapshotProcessing {
    private final NamedParameterJdbcTemplate jdbc;
    private final SnapshotLimits limits;
    private final TransactionTemplate reads;
    private final int fetchSize;

    public SnapshotProcessing(NamedParameterJdbcTemplate jdbc, SnapshotLimits limits,
                              PlatformTransactionManager transactions,
                              @Value("${app.snapshot.processing-bytes:33554432}") int processingBytes) {
        if (processingBytes < limits.maxBytes()) {
            throw new IllegalArgumentException("Snapshot processing budget must fit one maximum-size snapshot");
        }
        this.jdbc = jdbc;
        this.limits = limits;
        this.fetchSize = Math.min(100, processingBytes / limits.maxBytes());
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
    }

    public void read(String sql, Map<String, ?> parameters, RowCallbackHandler handler) {
        reads.executeWithoutResult(status -> jdbc.execute(sql, parameters, statement -> {
            statement.setFetchSize(fetchSize);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) handler.processRow(rows);
            }
            return null;
        }));
    }

    public void forEachLatestPublish(List<Long> projectIds, BiConsumer<Long, ProjectSnapshotData> consumer) {
        if (projectIds.isEmpty()) return;
        read("""
                SELECT s.project_id,
                       CASE WHEN octet_length(s.content) <= :bytes THEN s.content END AS content
                FROM project_snapshot s WHERE s.trigger = 'PUBLISH' AND s.id IN
                (SELECT max(latest.id) FROM project_snapshot latest WHERE latest.trigger = 'PUBLISH'
                 AND latest.project_id IN (:ids) GROUP BY latest.project_id)
                """, Map.of("ids", projectIds, "bytes", limits.maxBytes()), rows -> {
            Long projectId = rows.getLong("project_id");
            limits.readPreview(rows.getString("content")).ifPresent(data -> consumer.accept(projectId, data));
        });
    }
}
