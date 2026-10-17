package com.tramo.backend.project.service;

import com.tramo.backend.common.ContentLimits;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.project.dto.*;
import com.tramo.backend.project.entity.*;
import com.tramo.backend.project.repository.ProjectVersionRepository;
import com.tramo.backend.project.snapshot.SnapshotLimits;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.tag.service.TagService;
import com.tramo.backend.trail.entity.*;
import com.tramo.backend.trail.repository.*;
import com.tramo.backend.upload.ImageDeletionQueue;
import com.tramo.backend.upload.R2Client;
import com.tramo.backend.upload.service.EditorImageService;
import com.tramo.backend.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProjectVersionService {
    private final AccessGuard access;
    private final ProjectVersionCapture capture;
    private final ProjectVersionRepository versions;
    private final ItemRepository items;
    private final TrailRepository trails;
    private final TrailItemRepository memberships;
    private final ItemImageReferenceRepository references;
    private final EditorImageService images;
    private final ImageDeletionQueue deletion;
    private final R2Client r2;
    private final SnapshotLimits limits;
    private final TagService tags;
    private final ObjectMapper mapper;
    private final SubscriptionService subscriptions;
    private final EntityManager em;

    public ProjectVersionService(AccessGuard access, ProjectVersionCapture capture, ProjectVersionRepository versions,
            ItemRepository items, TrailRepository trails, TrailItemRepository memberships,
            ItemImageReferenceRepository references, EditorImageService images, ImageDeletionQueue deletion,
            R2Client r2, SnapshotLimits limits, TagService tags, ObjectMapper mapper, SubscriptionService subscriptions, EntityManager em) {
        this.access = access; this.capture = capture; this.versions = versions; this.items = items;
        this.trails = trails; this.memberships = memberships; this.references = references; this.images = images;
        this.deletion = deletion; this.r2 = r2; this.limits = limits; this.tags = tags; this.mapper = mapper;
        this.subscriptions = subscriptions; this.em = em;
    }

    public record History(List<ProjectVersionSummaryDTO> versions, Long nextCursor, boolean unavailable) {}
    public record Detail(ProjectVersionSummaryDTO version, ProjectVersionData snapshot) {}

    public History list(Long id, Long beforeId, User user) {
        Project project = access.getOwnedProject(id, user);
        var rows = versions.summaries(id, beforeId, PageRequest.of(0, 21));
        return new History(rows.stream().limit(20).toList(), rows.size() > 20 ? rows.get(19).id() : null, project.isHistoryUnavailable());
    }

    public Detail detail(Long id, Long versionId, User user) {
        access.getOwnedProject(id, user);
        ProjectVersion version = version(id, versionId);
        return new Detail(summary(version), capture.read(version.getSnapshot()));
    }

    @Transactional
    public ProjectVersionSummaryDTO create(Long id, String name, User user) {
        Project project = capture.lock(id, user);
        return summary(capture.save(project, limits.writeValue(capture.capture(project)), ProjectVersion.Type.MANUAL, name));
    }

    @Transactional
    public long restore(Long id, Long versionId, User user) {
        Project project = capture.lock(id, user);
        ProjectVersionData data = capture.read(version(id, versionId).getSnapshot());
        capture.save(project, limits.writeValue(capture.capture(project)), ProjectVersion.Type.RESTORE, null);
        List<Item> current = items.findForExport(id);
        Map<Long, Item> currentById = current.stream().collect(Collectors.toMap(Item::getId, i -> i));
        Set<Long> shared = em.createQuery("select distinct ti.item.id from TrailItem ti where ti.item.id in :ids and ti.trail.project.id <> :project", Long.class)
                .setParameter("ids", currentById.isEmpty() ? List.of(-1L) : currentById.keySet()).setParameter("project", id).getResultStream().collect(Collectors.toSet());
        Map<Long, Trail> oldTrails = trails.findByProjectId(id).stream().collect(Collectors.toMap(Trail::getId, t -> t));
        project.setThumbnailTrail(null);
        memberships.deleteAll(memberships.findEditorSteps(id));
        em.flush();
        Map<Long, Item> restored = new LinkedHashMap<>();
        for (var row : data.items()) {
            ContentLimits.assertNote(row.content());
            Item item = currentById.get(row.id());
            if (item == null || item.getProject() == null || !id.equals(item.getProject().getId()) || shared.contains(row.id())) {
                item = new Item(); item.setProject(project); item.setCreatedDate(new Date());
                item.setContent(new ItemContent());
            }
            item.setTitle(row.title()); item.setType(row.type()); item.setTitleAlign(row.titleAlign());
            item.setUnfiled(row.unfiled()); item.setModifiedDate(new Date());
            if (item.getContent() == null) item.setContent(new ItemContent());
            item.getContent().setContent(row.content()); item.getContent().setUpdatedDate(new Date());
            item.getContent().setExtractionEpoch(item.getContent().getExtractionEpoch() + 1);
            restored.put(row.id(), items.save(item));
        }
        ProjectForkService.retargetMentions(restored, mapper);
        Set<Long> retained = restored.values().stream().map(Item::getId).collect(Collectors.toSet());
        for (Item item : current) {
            if (retained.contains(item.getId()) || item.getProject() == null || !id.equals(item.getProject().getId())) continue;
            if (shared.contains(item.getId())) {
                item.setProject(null);
            } else {
                deletion.queueItemImages(item.getId(), user.getId());
                references.deleteByItemId(item.getId());
                items.delete(item);
            }
        }
        int position = 0;
        Set<Long> retainedTrails = new HashSet<>();
        for (var row : data.trails()) {
            Trail trail = oldTrails.get(row.id());
            if (trail == null) { trail = new Trail(); trail.setProject(project); trail.setCreationDate(new Date()); }
            ContentLimits.assertTrailDescription(row.description());
            trail.setTitle(row.title()); trail.setDescription(row.description()); trail.setVisibility(row.visibility());
            trail.setOrderIndex(position++); trail.setModifiedDate(new Date());
            if (trail.getId() == null && row.forkedFromId() != null) trail.setForkedFrom(trails.findById(row.forkedFromId()).orElse(null));
            trail = trails.save(trail); retainedTrails.add(trail.getId());
            if (Objects.equals(data.thumbnailTrailId(), row.id())) project.setThumbnailTrail(trail);
            int itemPosition = 0;
            for (Long itemId : row.itemIds()) {
                Item item = restored.get(itemId);
                if (item == null) throw new IllegalArgumentException("Invalid version membership");
                TrailItem membership = new TrailItem(); membership.setTrail(trail); membership.setItem(item);
                membership.setOrderIndex(itemPosition++); memberships.save(membership);
            }
        }
        for (Trail trail : oldTrails.values()) if (!retainedTrails.contains(trail.getId())) trails.delete(trail);
        for (Item item : restored.values()) {
            String content = item.getContent().getContent();
            images.syncRestoredItem(item, content, versionId);
            Set<String> old = new HashSet<>(references.findUrlsByItemId(item.getId()));
            Set<String> urls = r2.extractReferencedUrls(content);
            references.deleteByItemId(item.getId());
            em.flush();
            for (String url : urls) {
                ItemImageReference reference = new ItemImageReference(); reference.setItem(item); reference.setUrl(url); references.save(reference);
            }
            for (String url : old) if (!urls.contains(url)) deletion.queue(url, user.getId());
        }
        String oldThumbnail = project.getThumbnailImageUrl();
        project.setTitle(data.title()); project.setDescription(data.description()); project.setGraphColors(data.graphColors());
        project.setThumbnailType(data.thumbnailType()); project.setThumbnailImageUrl(data.thumbnailImageUrl());
        tags.applyProjectTags(project, data.tags(), user.getId());
        if (oldThumbnail != null && !Objects.equals(oldThumbnail, data.thumbnailImageUrl())) deletion.queue(oldThumbnail, user.getId());
        project.setRestoreEpoch(project.getRestoreEpoch() + 1);
        project.setLastEditedDate(new Date()); project.setModifiedDate(new Date()); project.setHistoryUnavailable(false);
        em.flush();
        subscriptions.assertUploadAllowed(user, 0, subscriptions.isSupporter(user));
        return project.getRestoreEpoch();
    }

    private ProjectVersion version(Long id, Long versionId) {
        return versions.findByIdAndProjectId(versionId, id).orElseThrow(() -> new ResourceNotFoundException("Version not found"));
    }

    private ProjectVersionSummaryDTO summary(ProjectVersion version) {
        return new ProjectVersionSummaryDTO(version.getId(), version.getCreatedAt(), version.getType(), version.getName());
    }
}
