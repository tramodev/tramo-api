package com.tramo.backend.project.snapshot;

import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.exception.StorageQuotaExceededException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class SnapshotCapture {
    private final SnapshotProcessing processing;
    private final SnapshotLimits limits;
    @PersistenceContext
    private EntityManager entityManager;

    public SnapshotCapture(SnapshotProcessing processing, SnapshotLimits limits) {
        this.processing = processing;
        this.limits = limits;
    }

    public ProjectSnapshotData capture(Long projectId) {
        return capture(projectId, Long.MAX_VALUE);
    }

    public ProjectSnapshotData capture(Long projectId, long storageBytes) {
        AtomicReference<ProjectSnapshotData> captured = new AtomicReference<>();
        captureMany(List.of(projectId), storageBytes, false, (id, data) -> captured.set(data));
        ProjectSnapshotData data = captured.get();
        if (data == null) throw new ResourceNotFoundException("Project not found");
        return data;
    }

    public void capturePreviews(List<Long> projectIds, BiConsumer<Long, ProjectSnapshotData> consumer) {
        captureMany(projectIds, Long.MAX_VALUE, true, consumer);
    }

    private void captureMany(List<Long> projectIds, long storageBytes, boolean omitOversized,
                             BiConsumer<Long, ProjectSnapshotData> consumer) {
        if (projectIds.isEmpty()) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            entityManager.flush();
        }
        processing.read("SELECT outer_project.id, captured.raw_bytes, captured.content FROM project outer_project CROSS JOIN LATERAL (" +
                SQL.replace(":id", "outer_project.id") + ") captured WHERE outer_project.id IN (:ids)",
                Map.of("ids", projectIds, "bytes", limits.maxBytes(), "trails", limits.maxTrails(),
                        "items", limits.maxItems(), "refs", limits.maxReferences(), "storage", storageBytes,
                        "schema", ProjectSnapshotData.CURRENT_SCHEMA_VERSION), rs -> {
                    if (rs.getLong("raw_bytes") > storageBytes) throw new StorageQuotaExceededException();
                    String content = rs.getString("content");
                    Long id = rs.getLong("id");
                    if (omitOversized) {
                        limits.readPreview(content).ifPresent(data -> consumer.accept(id, data));
                    } else {
                        consumer.accept(id, limits.read(content));
                    }
                });
    }

    private static final String SQL = """
                WITH trails AS MATERIALIZED (
                    SELECT * FROM trail WHERE project_id = :id LIMIT :trails + 1
                ), memberships AS MATERIALIZED (
                    SELECT ti.* FROM trail_item ti JOIN trails t ON t.id = ti.trail_id LIMIT :refs + 1
                ), occurrences AS MATERIALIZED (
                    SELECT item_id FROM memberships
                    UNION ALL
                    SELECT i.id FROM item i WHERE i.project_id = :id
                    AND NOT EXISTS (SELECT 1 FROM memberships m WHERE m.item_id = i.id)
                    LIMIT :refs + 1
                ), budget AS MATERIALIZED (
                    SELECT (SELECT count(*) FROM trails) AS trails,
                           count(*) AS refs, count(DISTINCT o.item_id) AS items,
                           coalesce(sum(coalesce(octet_length(i.title), 0)::bigint
                               + coalesce(octet_length(i.type), 0) + coalesce(octet_length(i.title_align), 0)
                               + coalesce(octet_length(c.content), 0)), 0)
                           + (SELECT coalesce(sum(coalesce(octet_length(title), 0)::bigint
                               + coalesce(octet_length(description), 0) + coalesce(octet_length(visibility), 0)), 0) FROM trails)
                           + (SELECT coalesce(octet_length(title), 0)::bigint + coalesce(octet_length(description), 0)
                               + coalesce(octet_length(thumbnail_image_url), 0) + coalesce(octet_length(thumbnail_type), 0)
                               FROM project WHERE id = :id)
                           + (SELECT coalesce(sum(octet_length(tag.name)::bigint), 0)
                               FROM project_tag pt JOIN tag ON tag.id = pt.tag_id WHERE pt.project_id = :id) AS raw_bytes,
                           coalesce(sum(256 + 6 * (coalesce(octet_length(i.title), 0)::bigint
                               + coalesce(octet_length(i.type), 0) + coalesce(octet_length(i.title_align), 0)
                               + coalesce(octet_length(c.content), 0))), 0)
                           + (SELECT coalesce(sum(256 + 6 * (coalesce(octet_length(title), 0)::bigint
                               + coalesce(octet_length(description), 0) + coalesce(octet_length(visibility), 0))), 0) FROM trails)
                           + (SELECT 512 + 6 * (coalesce(octet_length(title), 0)::bigint
                               + coalesce(octet_length(description), 0) + coalesce(octet_length(thumbnail_image_url), 0)
                               + coalesce(octet_length(thumbnail_type), 0)) FROM project WHERE id = :id)
                           + (SELECT coalesce(sum(16 + 6 * octet_length(tag.name)::bigint), 0)
                               FROM project_tag pt JOIN tag ON tag.id = pt.tag_id WHERE pt.project_id = :id) AS bytes
                    FROM occurrences o JOIN item i ON i.id = o.item_id LEFT JOIN item_content c ON c.id = i.content_id
                )
                SELECT b.raw_bytes, CASE WHEN b.trails <= :trails AND b.items <= :items AND b.refs <= :refs
                AND b.bytes <= :bytes AND b.raw_bytes <= :storage
                THEN (SELECT json_build_object(
                    'schemaVersion', :schema, 'projectId', p.id, 'title', p.title, 'description', p.description,
                    'visibility', p.visibility, 'thumbnail', p.thumbnail_image_url, 'thumbnailType', p.thumbnail_type,
                    'tags', (SELECT string_agg(tag.name, ',' ORDER BY tag.name) FROM project_tag pt
                             JOIN tag ON tag.id = pt.tag_id WHERE pt.project_id = p.id),
                    'trails', coalesce((SELECT json_agg(json_build_object(
                        'id', t.id, 'title', t.title, 'description', t.description, 'visibility', t.visibility,
                        'version', t.version, 'forkedFromId', t.forked_from_trail_id,
                        'items', coalesce((SELECT json_agg(json_build_object(
                            'id', i.id, 'title', i.title, 'type', i.type, 'titleAlign', i.title_align, 'content', c.content)
                            ORDER BY m.order_index, m.id)
                            FROM memberships m JOIN item i ON i.id = m.item_id
                            LEFT JOIN item_content c ON c.id = i.content_id WHERE m.trail_id = t.id), '[]'::json))
                        ORDER BY t.order_index, t.id) FROM trails t), '[]'::json),
                    'looseItems', coalesce((SELECT json_agg(json_build_object(
                        'id', i.id, 'title', i.title, 'type', i.type, 'titleAlign', i.title_align, 'content', c.content) ORDER BY i.id)
                        FROM item i LEFT JOIN item_content c ON c.id = i.content_id WHERE i.project_id = p.id
                        AND NOT EXISTS (SELECT 1 FROM memberships m WHERE m.item_id = i.id)), '[]'::json))::text
                    FROM project p WHERE p.id = :id)
                ELSE NULL END AS content FROM budget b
                """;
}
