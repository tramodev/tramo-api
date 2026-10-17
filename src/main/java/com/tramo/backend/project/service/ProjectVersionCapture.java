package com.tramo.backend.project.service;

import com.tramo.backend.common.ContentLimits;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.*;
import com.tramo.backend.project.dto.*;
import com.tramo.backend.project.entity.*;
import com.tramo.backend.project.repository.ProjectVersionRepository;
import com.tramo.backend.project.snapshot.SnapshotLimits;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.trail.repository.*;
import com.tramo.backend.upload.service.EditorImageService;
import com.tramo.backend.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class ProjectVersionCapture {
    private final AccessGuard access;
    private final EntityManager em;
    private final SubscriptionService subscriptions;
    private final ItemRepository items;
    private final TrailRepository trails;
    private final TrailItemRepository memberships;
    private final ProjectVersionRepository versions;
    private final SnapshotLimits limits;
    private final EditorImageService images;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final ProjectIdCodec codec;

    public ProjectVersionCapture(AccessGuard access, EntityManager em, SubscriptionService subscriptions,
            ItemRepository items, TrailRepository trails, TrailItemRepository memberships,
            ProjectVersionRepository versions, SnapshotLimits limits, EditorImageService images,
            ObjectMapper mapper, JdbcTemplate jdbc, ProjectIdCodec codec) {
        this.access = access; this.em = em; this.subscriptions = subscriptions;
        this.items = items; this.trails = trails; this.memberships = memberships;
        this.versions = versions; this.limits = limits; this.images = images; this.mapper = mapper; this.jdbc = jdbc; this.codec = codec;
    }

    public Project lock(Long id, User user) {
        subscriptions.lockStorage(user);
        return lockProject(id, user);
    }

    private Project lockProject(Long id, User user) {
        Project project = em.find(Project.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (project == null) throw new ResourceNotFoundException("Project not found");
        access.getOwnedProject(id, user);
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes && attributes.getRequest().getRequestURI().startsWith("/api/")) {
            String supplied = attributes.getRequest().getHeader("X-Project-Epoch");
            if (supplied == null && project.getRestoreEpoch() == 0) return project;
            if (supplied == null || !supplied.equals(Long.toString(project.getRestoreEpoch())))
                throw new ProjectVersionConflictException("This project was restored in another session. Reload before saving; your changes are kept locally.");
        }
        return project;
    }

    public Project lockItem(Long id, User user) {
        subscriptions.lockStorage(user);
        em.flush();
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String context = attributes.getRequest().getHeader("X-Project-Id");
            if (context != null) {
                Project project = lockProject(codec.decode(context), user);
                if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM item i WHERE i.id = ? AND (i.project_id = ? OR EXISTS (SELECT 1 FROM trail_item ti JOIN trail t ON t.id = ti.trail_id WHERE ti.item_id = i.id AND t.project_id = ?)))", Boolean.class, id, project.getId(), project.getId())))
                    throw new ProjectVersionConflictException("This note no longer belongs to the project. Reload before saving; your changes are kept locally.");
                return project;
            }
        }
        List<Long> ids = jdbc.queryForList("SELECT COALESCE(i.project_id, (SELECT t.project_id FROM trail_item ti JOIN trail t ON t.id = ti.trail_id JOIN project p ON p.id = t.project_id WHERE ti.item_id = i.id AND p.user_id = ? ORDER BY t.project_id LIMIT 1)) FROM item i WHERE i.id = ?", Long.class, user.getId(), id);
        if (ids.isEmpty() || ids.get(0) == null) throw new ResourceNotFoundException("Item not found");
        return lockProject(ids.get(0), user);
    }

    public Project lockTrail(Long id, User user) {
        subscriptions.lockStorage(user);
        em.flush();
        List<Long> ids = jdbc.queryForList("SELECT project_id FROM trail WHERE id = ?", Long.class, id);
        if (ids.isEmpty()) throw new ResourceNotFoundException("Trail not found");
        return lockProject(ids.get(0), user);
    }

    public void beforeChange(Project project) {
        Date now = new Date();
        Date lastAuto = project.getLastAutoVersionDate();
        boolean idle = project.getLastEditedDate() != null && now.getTime() - project.getLastEditedDate().getTime() >= 15 * 60_000L;
        if (lastAuto == null || idle || now.getTime() - lastAuto.getTime() >= 30 * 60_000L) {
            try {
                String snapshot = limits.writeValue(capture(project));
                String hash = hash(snapshot);
                var previous = versions.latestHashes(project.getId(), PageRequest.of(0, 1));
                if (previous.isEmpty() || !previous.get(0).equals(hash)) save(project, snapshot, ProjectVersion.Type.AUTO, null);
                project.setHistoryUnavailable(false);
                project.setLastAutoVersionDate(now);
            } catch (PayloadTooLargeException exceeded) {
                project.setHistoryUnavailable(true);
            }
        }
        project.setLastEditedDate(now);
    }

    public ProjectVersionData capture(Project project) {
        em.flush();
        var size = items.exportSize(project.getId());
        if (size.getItems() > limits.maxItems() || size.getBytes() > limits.maxBytes()
                || trails.countByProjectId(project.getId()) > limits.maxTrails()
                || memberships.countByTrailProjectId(project.getId()) > limits.maxReferences()) throw SnapshotLimits.exceeded();
        jdbc.queryForList("SELECT i.id FROM item i WHERE i.project_id = ? OR EXISTS (SELECT 1 FROM trail_item ti JOIN trail t ON t.id = ti.trail_id WHERE ti.item_id = i.id AND t.project_id = ?) ORDER BY i.id FOR UPDATE OF i", Long.class, project.getId(), project.getId());
        var steps = memberships.findEditorSteps(project.getId());
        var byTrail = steps.stream().collect(Collectors.groupingBy(step -> step.getTrail().getId(),
                LinkedHashMap::new, Collectors.mapping(step -> step.getItem().getId(), Collectors.toList())));
        var trailData = trails.findByProjectId(project.getId()).stream().map(t -> new ProjectVersionData.TrailData(
                t.getId(), t.getTitle(), t.getDescription(), t.getVisibility(),
                t.getForkedFrom() == null ? null : t.getForkedFrom().getId(), byTrail.getOrDefault(t.getId(), List.of()))).toList();
        var itemData = items.findForExport(project.getId()).stream().map(i -> new ProjectVersionData.ItemData(
                i.getId(), i.getTitle(), i.getType(), i.getTitleAlign(),
                i.getContent() == null ? null : i.getContent().getContent(), Boolean.TRUE.equals(i.getUnfiled()))).toList();
        var tags = jdbc.queryForList("SELECT t.name FROM project_tag pt JOIN tag t ON t.id = pt.tag_id WHERE pt.project_id = ? ORDER BY t.name", String.class, project.getId());
        return new ProjectVersionData(1, project.getTitle(), project.getDescription(), project.getGraphColors(),
                project.getThumbnailType(), project.getThumbnailImageUrl(),
                project.getThumbnailTrail() == null ? null : project.getThumbnailTrail().getId(), tags, itemData, trailData);
    }

    public ProjectVersion save(Project project, String snapshot, ProjectVersion.Type type, String name) {
        if (type != ProjectVersion.Type.AUTO) subscriptions.assertUploadAllowed(project.getOwner(), ContentLimits.bytes(snapshot), subscriptions.isSupporter(project.getOwner()));
        ProjectVersion version = new ProjectVersion();
        version.setProject(project); version.setSnapshot(snapshot); version.setType(type); version.setName(name);
        version.setCreatedAt(new Date()); version.setStateHash(hash(snapshot)); version.setSnapshotBytes(ContentLimits.bytes(snapshot));
        versions.saveAndFlush(version);
        ProjectVersionData data = read(snapshot);
        images.retainVersion(version.getId(), data.items().stream().map(ProjectVersionData.ItemData::content).toList());
        jdbc.update("DELETE FROM project_version WHERE id IN (SELECT id FROM (SELECT id, row_number() OVER (ORDER BY id DESC) AS position, sum(snapshot_bytes) OVER (ORDER BY id DESC) AS bytes FROM project_version WHERE project_id = ? AND type = 'AUTO') retained WHERE position > 50 OR bytes > 67108864)", project.getId());
        return version;
    }

    public ProjectVersionData read(String snapshot) {
        if (snapshot == null || ContentLimits.bytes(snapshot) > limits.maxBytes()) throw SnapshotLimits.exceeded();
        ProjectVersionData data = mapper.readValue(snapshot, ProjectVersionData.class);
        if (data.schemaVersion() != 1) throw new ProjectVersionConflictException("This version uses an unsupported snapshot format.");
        return data;
    }

    private String hash(String snapshot) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
