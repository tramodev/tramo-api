// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.upload.service;

import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import com.tramo.backend.common.SafeLog;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectSnapshot;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.repository.ProjectSnapshotRepository;
import com.tramo.backend.project.service.AccessGuard;
import com.tramo.backend.security.ratelimit.RateLimiterService;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.upload.PrivateImageStorage;
import com.tramo.backend.upload.dto.*;
import com.tramo.backend.upload.entity.EditorImage;
import com.tramo.backend.upload.entity.UploadRecord;
import com.tramo.backend.upload.repository.EditorImageRepository;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.user.entity.User;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.tramo.backend.project.snapshot.ProjectSnapshotData;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class EditorImageService {
    private static final Logger log = LoggerFactory.getLogger(EditorImageService.class);
    private final EditorImageRepository images;
    private final UploadRecordRepository uploads;
    private final PrivateImageStorage storage;
    private final AccessGuard access;
    private final ProjectIdCodec codec;
    private final ProjectRepository projects;
    private final ProjectSnapshotRepository snapshots;
    private final SubscriptionService subscriptions;
    private final RateLimiterService limiter;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;
    private final ObjectMapper mapper;
    private final long maxBytes;
    private final int bytesPerHour;

    public EditorImageService(EditorImageRepository images, UploadRecordRepository uploads, PrivateImageStorage storage,
            AccessGuard access, ProjectIdCodec codec, ProjectRepository projects, ProjectSnapshotRepository snapshots,
            SubscriptionService subscriptions, RateLimiterService limiter, PlatformTransactionManager transactionManager,
            EntityManager entityManager, ObjectMapper mapper,
            @Value("${app.limits.max-upload-bytes}") long maxBytes,
            @Value("${app.limits.max-upload-bytes-per-hour}") int bytesPerHour) {
        this.images = images; this.uploads = uploads; this.storage = storage; this.access = access;
        this.codec = codec; this.projects = projects; this.snapshots = snapshots; this.subscriptions = subscriptions;
        this.limiter = limiter; this.transactions = new TransactionTemplate(transactionManager); this.entityManager = entityManager;
        this.mapper = mapper; this.maxBytes = maxBytes; this.bytesPerHour = bytesPerHour;
    }

    public EditorImagePresignResponse presign(EditorImagePresignRequest request, User user) {
        Project project = access.getOwnedProject(codec.decode(request.projectId()), user);
        if (request.contentBytes() > maxBytes) throw new RequestValidationException(RequestErrorCode.IMAGE_SIZE_LIMIT_EXCEEDED);
        if (!limiter.resolveBucket("upload-bytes:" + user.getId(), bytesPerHour, bytesPerHour, Duration.ofHours(1))
                .tryConsume(request.contentBytes())) throw new LimitExceededException("Upload throughput limit reached. Try again later.");
        EditorImage image = transactions.execute(status -> {
            images.lockUser(user.getId());
            subscriptions.assertUploadAllowed(user, request.contentBytes(), subscriptions.isSupporter(user));
            UUID imageId = UUID.randomUUID();
            UUID objectId = UUID.randomUUID();
            images.createObject(objectId, request.contentType(), request.contentBytes(), request.contentHash());
            createImage(imageId, objectId, project, request.contentBytes());
            return new EditorImage(imageId, objectId, project.getId(), request.contentType(), request.contentBytes(), "PENDING", request.contentHash());
        });
        return new EditorImagePresignResponse(image.id(), storage.presignUpload(image));
    }

    public void complete(UUID id, User user) {
        EditorImage image = findOne(id);
        if (image.projectId() == null) throw missing();
        access.getOwnedProject(image.projectId(), user);
        UUID attempt = images.withObjectLock(image.objectId(), () ->
                transactions.execute(status -> images.claimCompletion(image.objectId())));
        if (attempt == null) return;
        try {
            EditorImage claimedImage = findOne(id);
            PrivateImageStorage.ValidatedImage validated = storage.validate(claimedImage);
            images.withObjectLock(image.objectId(), () -> {
                transactions.executeWithoutResult(status -> images.recordValidation(image.objectId(), attempt, validated.hash()));
                storage.confirm(claimedImage, validated);
                transactions.executeWithoutResult(status -> images.finishAttempt(image.objectId(), attempt, "READY"));
                return null;
            });
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(status -> images.releaseAttempt(image.objectId(), attempt));
            throw failure;
        }
    }

    public EditorImageResolveResponse resolve(Long projectId, EditorImageResolveRequest request, User user, boolean publicRead) {
        Project project = publicRead ? projects.findById(projectId).orElseThrow(this::missing) : access.getOwnedProject(projectId, user);
        if (publicRead) access.assertViewable(project, user);
        Long snapshotId = request.snapshotId();
        if (snapshotId != null) {
            ProjectSnapshot snapshot = snapshots.findById(snapshotId).orElseThrow(this::missing);
            if (!snapshot.getProject().getId().equals(projectId) || (publicRead && (!"PUBLISH".equals(snapshot.getTrigger())
                    || project.getFirstPublishedDate() == null))) throw missing();
        } else if (publicRead && project.getVisibility() == ProjectVisibility.PUBLISHED) {
            snapshotId = snapshots.findLatestPublishByProjectIdIn(List.of(projectId)).stream()
                    .map(ProjectSnapshot::getId).findFirst().orElseThrow(this::missing);
        }
        Set<UUID> requested = new LinkedHashSet<>(request.imageIds());
        if (!new HashSet<>(images.visibleImageIds(projectId, snapshotId)).containsAll(requested)) throw missing();
        List<EditorImage> found = images.findAll(requested);
        if (found.size() != requested.size() || found.stream().anyMatch(i -> !"READY".equals(i.state()))) throw missing();
        Instant expires = Instant.now().plusSeconds(300);
        return new EditorImageResolveResponse(found.stream()
                .map(i -> new EditorImageResolveResponse.Image(i.id(), storage.presignRead(i), expires)).toList());
    }

    public void syncItem(Item item, String content) {
        entityManager.flush();
        Set<UUID> ids = extract(content);
        if (!new HashSet<>(images.editableImageIds(ids, item.getProject().getId(), item.getId())).containsAll(ids)) throw missing();
        lockReady(ids);
        images.replaceItemReferences(item.getId(), ids);
    }

    public void retainSnapshot(ProjectSnapshot snapshot) {
        entityManager.flush();
        ProjectSnapshotData data = mapper.readValue(snapshot.getContent(), ProjectSnapshotData.class);
        Set<UUID> ids = new LinkedHashSet<>();
        data.trails().forEach(t -> t.items().forEach(i -> ids.addAll(extract(i.content()))));
        data.looseItems().forEach(i -> ids.addAll(extract(i.content())));
        lockReady(ids);
        images.addSnapshotReferences(snapshot.getId(), ids);
    }

    public void copyForkImages(Project fork) {
        entityManager.flush();
        List<Item> items = entityManager.createQuery("SELECT i FROM Item i LEFT JOIN FETCH i.content WHERE i.project.id = :id", Item.class)
                .setParameter("id", fork.getId()).getResultList();
        Set<UUID> sourceIds = items.stream().filter(i -> i.getContent() != null)
                .flatMap(i -> extract(i.getContent().getContent()).stream()).collect(Collectors.toSet());
        List<EditorImage> sources = lockReady(sourceIds);
        images.lockUser(fork.getOwner().getId());
        long bytes = sources.stream().map(EditorImage::objectId).distinct()
                .mapToLong(objectId -> sources.stream().filter(i -> i.objectId().equals(objectId)).findFirst().orElseThrow().bytes()).sum();
        subscriptions.assertUploadAllowed(fork.getOwner(), bytes, subscriptions.isSupporter(fork.getOwner()));
        Map<UUID, UUID> replacements = new HashMap<>();
        Map<UUID, UUID> copiedObjects = new HashMap<>();
        for (EditorImage source : sources) {
            UUID copyId = copiedObjects.computeIfAbsent(source.objectId(), objectId -> {
                UUID newId = UUID.randomUUID();
                createImage(newId, objectId, fork, source.bytes());
                return newId;
            });
            replacements.put(source.id(), copyId);
        }
        for (Item item : items) {
            if (item.getContent() == null) continue;
            String content = item.getContent().getContent();
            if (extract(content).isEmpty()) continue;
            JsonNode root = mapper.readTree(content);
            visit(root, node -> node.put("imageId", replacements.get(UUID.fromString(node.path("imageId").asText())).toString()));
            item.getContent().setContent(mapper.writeValueAsString(root));
            syncItem(item, item.getContent().getContent());
        }
    }

    public Set<UUID> extract(String content) {
        if (content == null || content.isBlank()) return Set.of();
        JsonNode root;
        try { root = mapper.readTree(content); }
        catch (RuntimeException malformed) {
            if (!content.stripLeading().startsWith("{")) return Set.of();
            throw new RequestValidationException(RequestErrorCode.EDITOR_CONTENT_INVALID);
        }
        Set<UUID> ids = new LinkedHashSet<>();
        visit(root, node -> {
            if (node.has("src") || node.path("version").asInt() != 2 || !node.path("imageId").isString())
                throw new RequestValidationException(RequestErrorCode.IMAGE_ATTACHMENT_REQUIRED);
            try { ids.add(UUID.fromString(node.path("imageId").asText())); }
            catch (IllegalArgumentException invalid) { throw new RequestValidationException(RequestErrorCode.IMAGE_ID_INVALID); }
        });
        return ids;
    }

    private void visit(JsonNode node, Consumer<ObjectNode> visitor) {
        if (node.isObject() && "image".equals(node.path("type").asText(null))) visitor.accept((ObjectNode) node);
        for (JsonNode child : node) visit(child, visitor);
    }

    private List<EditorImage> lockReady(Set<UUID> ids) {
        List<EditorImage> found = images.findAll(ids);
        if (found.size() != ids.size()) throw missing();
        found.stream().map(EditorImage::objectId).distinct().sorted().forEach(id -> {
            if (!"READY".equals(images.lockObject(id))) throw missing();
        });
        return found;
    }

    private void createImage(UUID id, UUID objectId, Project project, long bytes) {
        UploadRecord upload = new UploadRecord();
        upload.setUserId(project.getOwner().getId()); upload.setProjectId(project.getId());
        upload.setObjectKey("private/" + id); upload.setBytes(bytes); upload.setCreatedDate(new Date());
        uploads.saveAndFlush(upload);
        images.createImage(id, objectId, project.getId(), upload.getId());
    }

    private EditorImage findOne(UUID id) { return images.findAll(List.of(id)).stream().findFirst().orElseThrow(this::missing); }
    private ResourceNotFoundException missing() { return new ResourceNotFoundException("Image not found"); }

    @Scheduled(fixedDelay = 3600000)
    public void purge() {
        for (UUID id : images.cleanupCandidates()) {
            try {
                images.withObjectLock(id, () -> {
                    boolean claimed = Boolean.TRUE.equals(transactions.execute(status -> images.claimDeletion(id)));
                    if (claimed) {
                        storage.deleteObject(id);
                        transactions.executeWithoutResult(status -> images.deleteObject(id));
                    }
                    return null;
                });
            } catch (RuntimeException failure) {
                SafeLog.failure(log, "private_image_purge_failed", "STORAGE_DELETE_FAILED", failure);
            }
        }
    }
}
