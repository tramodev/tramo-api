package com.tramo.backend.upload.repository;

import com.tramo.backend.upload.entity.EditorImage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class EditorImageRepository {
    private final java.util.concurrent.Semaphore operations = new java.util.concurrent.Semaphore(2);
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public EditorImageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public List<EditorImage> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return named.query("SELECT i.id, i.object_id, i.project_id, o.content_type, o.bytes, o.state, COALESCE(o.content_hash, o.validated_hash) " +
                "FROM editor_image i JOIN editor_image_object o ON o.id = i.object_id WHERE i.id IN (:ids)",
                Map.of("ids", ids), (rs, row) -> new EditorImage(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), (Long) rs.getObject(3), rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7)));
    }

    public void lockUser(Long userId) {
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
    }

    public String lockObject(UUID objectId) {
        return jdbc.queryForObject("SELECT state FROM editor_image_object WHERE id = ? FOR UPDATE", String.class, objectId);
    }

    public void createObject(UUID id, String type, long bytes, String hash) {
        jdbc.update("INSERT INTO editor_image_object(id, content_type, bytes, content_hash, state) VALUES (?, ?, ?, ?, 'PENDING')", id, type, bytes, hash);
    }

    public void createImage(UUID id, UUID objectId, Long projectId, Long uploadId) {
        jdbc.update("INSERT INTO editor_image(id, object_id, project_id, upload_record_id) VALUES (?, ?, ?, ?)",
                id, objectId, projectId, uploadId);
    }

    public void setState(UUID objectId, String state) {
        jdbc.update("UPDATE editor_image_object SET state = ?, lease_until = CASE WHEN ? = 'COPYING' THEN CURRENT_TIMESTAMP + INTERVAL '60 seconds' ELSE NULL END WHERE id = ?", state, state, objectId);
        if ("READY".equals(state)) jdbc.update("UPDATE editor_image SET last_used_at = CURRENT_TIMESTAMP WHERE object_id = ?", objectId);
    }

    public <T> T withObjectLock(UUID id, java.util.function.Supplier<T> action) {
        if (!operations.tryAcquire()) throw new IllegalArgumentException("Upload operation in progress");
        long key = id.getMostSignificantBits() ^ id.getLeastSignificantBits();
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(true);
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, key);
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) throw new IllegalArgumentException("Upload operation in progress");
                }
            }
            try {
                return action.get();
            } finally {
                try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    statement.setLong(1, key);
                    statement.execute();
                } catch (java.sql.SQLException failure) {
                    connection.abort(Runnable::run);
                    throw failure;
                }
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Cannot coordinate image operation", failure);
        } finally {
            operations.release();
        }
    }

    public UUID claimCompletion(UUID id) {
        String state = lockObject(id);
        if ("READY".equals(state)) return null;
        UUID attempt = UUID.randomUUID();
        int changed = jdbc.update("UPDATE editor_image_object SET state = 'COPYING', attempt_id = ?, " +
                "lease_until = CURRENT_TIMESTAMP + INTERVAL '60 seconds' WHERE id = ? AND " +
                "(state = 'PENDING' OR (state = 'COPYING' AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)))",
                attempt, id);
        if (changed != 1) throw new IllegalArgumentException("Upload operation in progress");
        return attempt;
    }

    public void recordValidation(UUID id, UUID attempt, String hash) {
        int changed = jdbc.update("UPDATE editor_image_object SET validated_hash = ? " +
                "WHERE id = ? AND state = 'COPYING' AND attempt_id = ?", hash, id, attempt);
        if (changed != 1) throw new IllegalArgumentException("Upload attempt expired");
    }

    public void releaseAttempt(UUID id, UUID attempt) {
        jdbc.update("UPDATE editor_image_object SET state = 'PENDING', lease_until = NULL " +
                "WHERE id = ? AND state = 'COPYING' AND attempt_id = ?", id, attempt);
    }

    public void finishAttempt(UUID id, UUID attempt, String state) {
        int changed = jdbc.update("UPDATE editor_image_object SET state = ?, lease_until = NULL " +
                "WHERE id = ? AND state = 'COPYING' AND attempt_id = ?", state, id, attempt);
        if (changed != 1) throw new IllegalArgumentException("Upload attempt expired");
        jdbc.update("UPDATE editor_image SET last_used_at = CURRENT_TIMESTAMP WHERE object_id = ?", id);
    }

    public void replaceItemReferences(Long itemId, Collection<UUID> ids) {
        jdbc.update("DELETE FROM editor_image_item_reference WHERE item_id = ?", itemId);
        for (UUID id : ids) jdbc.update("INSERT INTO editor_image_item_reference(item_id, image_id) VALUES (?, ?)", itemId, id);
    }

    public void addSnapshotReferences(Long snapshotId, Collection<UUID> ids) {
        for (UUID id : ids) jdbc.update("INSERT INTO editor_image_snapshot_reference(snapshot_id, image_id) VALUES (?, ?)", snapshotId, id);
    }

    public List<UUID> visibleImageIds(Long projectId, Long snapshotId) {
        if (snapshotId != null) return jdbc.queryForList(
                "SELECT image_id FROM editor_image_snapshot_reference WHERE snapshot_id = ?", UUID.class, snapshotId);
        return jdbc.queryForList("SELECT DISTINCT r.image_id FROM editor_image_item_reference r JOIN item i ON i.id = r.item_id " +
                "WHERE i.project_id = ? OR EXISTS (SELECT 1 FROM trail_item ti JOIN trail t ON t.id = ti.trail_id " +
                "WHERE ti.item_id = i.id AND t.project_id = ?)", UUID.class, projectId, projectId);
    }

    public List<UUID> editableImageIds(Collection<UUID> ids, Long projectId, Long itemId) {
        if (ids.isEmpty()) return List.of();
        return named.queryForList("SELECT i.id FROM editor_image i WHERE i.id IN (:ids) AND (i.project_id = :projectId " +
                "OR EXISTS (SELECT 1 FROM editor_image_item_reference r WHERE r.image_id = i.id AND r.item_id = :itemId))",
                Map.of("ids", ids, "projectId", projectId, "itemId", itemId), UUID.class);
    }

    public List<UUID> cleanupCandidates() {
        return jdbc.queryForList("SELECT id FROM editor_image_object WHERE state = 'DELETING' OR " +
                "(created_at < CURRENT_TIMESTAMP - INTERVAL '24 hours' AND state IN ('PENDING', 'COPYING')) OR " +
                "NOT EXISTS (SELECT 1 FROM editor_image i WHERE i.object_id = editor_image_object.id) OR " +
                "EXISTS (SELECT 1 FROM editor_image i WHERE i.object_id = editor_image_object.id " +
                "AND i.last_used_at < CURRENT_TIMESTAMP - INTERVAL '24 hours' " +
                "AND NOT EXISTS (SELECT 1 FROM editor_image_item_reference r WHERE r.image_id = i.id) " +
                "AND NOT EXISTS (SELECT 1 FROM editor_image_snapshot_reference r WHERE r.image_id = i.id)) " +
                "ORDER BY id LIMIT 500", UUID.class);
    }

    public boolean claimDeletion(UUID objectId) {
        String state = lockObject(objectId);
        if ("DELETING".equals(state)) return true;
        if ("COPYING".equals(state) && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT lease_until > CURRENT_TIMESTAMP FROM editor_image_object WHERE id = ?", Boolean.class, objectId))) return false;
        jdbc.update("DELETE FROM editor_image i WHERE object_id = ? " +
                "AND last_used_at < CURRENT_TIMESTAMP - INTERVAL '24 hours' " +
                "AND NOT EXISTS (SELECT 1 FROM editor_image_item_reference r WHERE r.image_id = i.id) " +
                "AND NOT EXISTS (SELECT 1 FROM editor_image_snapshot_reference r WHERE r.image_id = i.id)", objectId);
        int images = jdbc.queryForObject("SELECT COUNT(*) FROM editor_image WHERE object_id = ?", Integer.class, objectId);
        if (images != 0) return false;
        setState(objectId, "DELETING");
        return true;
    }

    public void deleteObject(UUID objectId) {
        jdbc.update("DELETE FROM editor_image_object WHERE id = ? AND state = 'DELETING'", objectId);
    }
}
