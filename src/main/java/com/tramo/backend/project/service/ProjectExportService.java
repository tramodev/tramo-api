package com.tramo.backend.project.service;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.ProjectExportException;
import com.tramo.backend.project.dto.ProjectExportDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.trail.entity.*;
import com.tramo.backend.trail.repository.*;
import com.tramo.backend.upload.PrivateImageStorage;
import com.tramo.backend.upload.R2Client;
import com.tramo.backend.upload.entity.EditorImage;
import com.tramo.backend.upload.repository.EditorImageRepository;
import com.tramo.backend.user.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.zip.*;

@Service
public class ProjectExportService {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private final Semaphore preparations = new Semaphore(2);
    private final AccessGuard access;
    private final ProjectIdCodec codec;
    private final ItemRepository items;
    private final TrailRepository trails;
    private final TrailItemRepository steps;
    private final AssociationRepository associations;
    private final ItemImageReferenceRepository legacyReferences;
    private final EditorImageRepository images;
    private final PrivateImageStorage privateStorage;
    private final R2Client publicStorage;
    private final ObjectMapper mapper;
    private final TransactionTemplate reads;

    public ProjectExportService(AccessGuard access, ProjectIdCodec codec, ItemRepository items, TrailRepository trails,
            TrailItemRepository steps, AssociationRepository associations, ItemImageReferenceRepository legacyReferences,
            EditorImageRepository images, PrivateImageStorage privateStorage, R2Client publicStorage,
            ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.access = access; this.codec = codec; this.items = items; this.trails = trails; this.steps = steps;
        this.associations = associations; this.legacyReferences = legacyReferences; this.images = images;
        this.privateStorage = privateStorage; this.publicStorage = publicStorage; this.mapper = mapper;
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private record Resource(String reference, String path, EditorImage image, String url) {}
    private record Snapshot(ProjectExportDTO data, List<Resource> resources) {}

    public Path prepare(Long projectId, User requester) throws IOException {
        access.getOwnedProject(projectId, requester);
        if (!preparations.tryAcquire()) throw new ProjectExportException(429, "EXPORT_BUSY", "Project exports are busy. Please try again shortly.");
        Path archive = null;
        boolean complete = false;
        try {
            Snapshot snapshot = reads.execute(status -> capture(projectId, requester));
            Set<String> warnings = new LinkedHashSet<>();
            String html = new ProjectExportHtml(mapper, snapshot.data(), warnings).render();
            var source = snapshot.data();
            var data = new ProjectExportDTO(2, source.exportedAt(), source.project(), source.items(), source.trails(), source.looseItemIds(),
                    source.associations(), source.assets(), List.copyOf(warnings));
            archive = Files.createTempFile("tramo-export-", ".zip");
            long total = 0;
            try (var zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(archive)))) {
                total = text(zip, "index.html", html, total);
                total = text(zip, "project.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data), total);
                Set<String> written = new HashSet<>();
                byte[] buffer = new byte[64 * 1024];
                for (Resource resource : snapshot.resources()) {
                    if (!written.add(resource.path())) continue;
                    try (InputStream stream = resource.image() != null ? privateStorage.openForExport(resource.image()) : publicStorage.openForExport(resource.url())) {
                        zip.putNextEntry(new ZipEntry(resource.path()));
                        long resourceBytes = 0;
                        int read;
                        while ((read = stream.read(buffer)) != -1) {
                            resourceBytes += read; total += read;
                            if (total > MAX_BYTES || resourceBytes > 25L * 1024 * 1024) throw ProjectExportException.tooLarge();
                            zip.write(buffer, 0, read);
                        }
                        if (resourceBytes == 0 || (resource.image() != null && resourceBytes != resource.image().bytes())) throw ProjectExportException.resource(resource.reference());
                        zip.closeEntry();
                    } catch (ProjectExportException failure) { throw failure; }
                    catch (RuntimeException | IOException failure) { throw ProjectExportException.resource(resource.reference()); }
                }
            }
            if (Files.size(archive) > MAX_BYTES + 1024 * 1024) throw ProjectExportException.tooLarge();
            complete = true;
            return archive;
        } finally {
            if (!complete) discard(archive);
        }
    }

    public void discard(Path archive) throws IOException {
        try { if (archive != null) Files.deleteIfExists(archive); }
        finally { preparations.release(); }
    }

    private Snapshot capture(Long projectId, User requester) {
        Project project = access.getOwnedProject(projectId, requester);
        var size = items.exportSize(projectId);
        if (size.getItems() > 5000 || size.getBytes() > 8L * 1024 * 1024 || steps.countByTrailProjectId(projectId) > 20000 || trails.countByProjectId(projectId) > 2000) throw ProjectExportException.tooLarge();
        List<Item> found = items.findForExport(projectId);
        List<Trail> orderedTrails = trails.findByProjectId(projectId);
        List<TrailItem> memberships = orderedTrails.isEmpty() ? List.of() : steps.findByTrailIdInWithItemContent(orderedTrails.stream().map(Trail::getId).toList());
        Map<Long, List<ProjectExportDTO.StepData>> stepsByTrail = new HashMap<>();
        Set<Long> placed = new HashSet<>();
        for (TrailItem step : memberships) {
            placed.add(step.getItem().getId());
            stepsByTrail.computeIfAbsent(step.getTrail().getId(), key -> new ArrayList<>()).add(new ProjectExportDTO.StepData(step.getId(), step.getItem().getId(), step.getOrderIndex()));
        }
        List<ProjectExportDTO.ItemData> notes = found.stream().map(item -> new ProjectExportDTO.ItemData(item.getId(), item.getTitle(), item.getType(), item.getTitleAlign(),
                item.getContent() == null ? null : item.getContent().getContent(), Boolean.TRUE.equals(item.getUnfiled()), date(item.getCreatedDate()), date(item.getModifiedDate()))).toList();
        List<ProjectExportDTO.TrailData> paths = orderedTrails.stream().map(trail -> new ProjectExportDTO.TrailData(trail.getId(), trail.getTitle(), trail.getDescription(), trail.getVisibility(), trail.getVersion(),
                trail.getForkedFrom() == null ? null : trail.getForkedFrom().getId(), stepsByTrail.getOrDefault(trail.getId(), List.of()))).toList();
        Map<Long, Association> relationshipMap = new TreeMap<>();
        if (!found.isEmpty()) associations.findBySourceItemIdIn(found.stream().map(Item::getId).toList())
                .forEach(association -> relationshipMap.put(association.getId(), association));
        List<ProjectExportDTO.AssociationData> ties = relationshipMap.values().stream()
                .map(a -> new ProjectExportDTO.AssociationData(a.getId(), a.getSourceItem().getId(), a.getTargetId(), a.getText())).toList();
        Set<String> references = new LinkedHashSet<>();
        for (var item : notes) {
            if (item.content() == null || item.content().isBlank()) continue;
            JsonNode root;
            try { root = mapper.readTree(item.content()); } catch (RuntimeException invalid) { continue; }
            collect(root.has("root") ? root.get("root") : root, references, 0);
        }
        String thumbnail = project.getThumbnailImageUrl();
        if (thumbnail != null && !thumbnail.isBlank() && Set.of("PROJECT_IMAGE", "DEDICATED").contains(String.valueOf(project.getThumbnailType()))) references.add(thumbnail);
        if (references.size() > 500) throw ProjectExportException.tooLarge();
        Set<UUID> ids = new LinkedHashSet<>();
        for (String reference : references) { try { ids.add(UUID.fromString(reference)); } catch (IllegalArgumentException url) {} }
        Set<UUID> visible = new HashSet<>(images.visibleImageIds(projectId, null));
        for (UUID id : ids) if (!visible.contains(id)) throw ProjectExportException.resource(id.toString());
        Map<UUID, EditorImage> privateImages = new HashMap<>();
        images.findAll(ids).forEach(image -> privateImages.put(image.id(), image));
        Set<String> permittedUrls = new HashSet<>(legacyReferences.findUrlsByProjectId(projectId));
        if (thumbnail != null) permittedUrls.add(thumbnail);
        Map<String, String> assets = new LinkedHashMap<>();
        List<Resource> resources = new ArrayList<>();
        long imageBytes = 0;
        Set<UUID> objects = new HashSet<>();
        for (String reference : references) {
            EditorImage image = null;
            try { image = privateImages.get(UUID.fromString(reference)); } catch (IllegalArgumentException url) {}
            String path;
            if (image != null) {
                if (!"READY".equals(image.state()) || image.bytes() <= 0) throw ProjectExportException.resource(reference);
                String extension = extension(image.contentType());
                path = "assets/" + image.objectId() + extension;
                if (objects.add(image.objectId())) imageBytes += image.bytes();
                if (imageBytes > MAX_BYTES) throw ProjectExportException.tooLarge();
            } else {
                if (!permittedUrls.contains(reference) || !publicStorage.isExportableUrl(reference)) throw ProjectExportException.resource("image reference");
                path = "assets/legacy-" + assets.size() + reference.substring(reference.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            }
            assets.put(reference, path);
            resources.add(new Resource(reference, path, image, image == null ? reference : null));
        }
        var metadata = new ProjectExportDTO.ProjectData(codec.encode(projectId), project.getTitle(), project.getDescription(), String.valueOf(project.getVisibility()),
                project.getProjectTags().stream().map(tag -> tag.getName()).sorted().toList(), String.valueOf(project.getThumbnailType()), thumbnail,
                project.getThumbnailTrail() == null ? null : project.getThumbnailTrail().getId(), date(project.getCreationDate()), date(project.getModifiedDate()));
        return new Snapshot(new ProjectExportDTO(2, Instant.now().toString(), metadata, notes, paths, found.stream().map(Item::getId).filter(id -> !placed.contains(id)).toList(), ties, assets, List.of()), resources);
    }
    private static void collect(JsonNode node, Set<String> references, int depth) {
        if (depth > 100) throw ProjectExportException.tooLarge();
        if ("image".equals(node.path("type").asText(""))) {
            String reference = node.path("imageId").asText("");
            if (reference.isBlank()) reference = node.path("src").asText("");
            if (reference.isBlank()) throw ProjectExportException.resource("unfinished image upload");
            references.add(reference);
        }
        for (JsonNode child : node.path("children")) collect(child, references, depth + 1);
    }
    private static String extension(String type) {
        return switch (type) { case "image/jpeg" -> ".jpg"; case "image/png" -> ".png"; case "image/webp" -> ".webp"; case "image/gif" -> ".gif"; default -> throw ProjectExportException.resource("unsupported image format"); };
    }
    private static String date(Date date) { return date == null ? null : date.toInstant().toString(); }
    private static long text(ZipOutputStream zip, String name, String value, long total) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (total + bytes.length > MAX_BYTES) throw ProjectExportException.tooLarge();
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry(); return total + bytes.length;
    }
}
