package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.project.service.ProjectExportService;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.upload.entity.UploadRecord;
import com.tramo.backend.upload.repository.EditorImageRepository;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.exception.ProjectExportException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.tramo.backend.trail.repository.ItemRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.ResponseInputStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectExportTest extends AbstractIntegrationTest {
    @Autowired ProjectExportService exports;
    @Autowired ProjectRepository projects;
    @Autowired ItemService itemService;
    @Autowired EditorImageRepository images;
    @Autowired UploadRecordRepository uploads;
    @Autowired com.tramo.backend.upload.R2Client publicStorage;
    @Autowired com.tramo.backend.trail.repository.ItemImageReferenceRepository legacyReferences;
    @MockitoSpyBean ItemRepository items;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6vV0AAAAASUVORK5CYII=");
    record Fixture(User owner, Project project, long first, long second, long shared, long loose) {}

    Fixture fixture() throws Exception {
        User owner = createUser("exportowner");
        Project project = createProject(owner, "<script>alert('title')</script>", "PRIVATE");
        project.setDescription("Description <img src=x onerror=alert(1)>");
        projects.saveAndFlush(project);
        long first = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Repeated\",\"description\":\"First path\"}");
        long second = postForId(owner, "/api/project/" + pid(project) + "/trail", "{\"title\":\"Repeated\"}");
        long shared = postForId(owner, "/api/trail/" + first + "/item", "{\"title\":\"Repeated\"}");
        long loose = postForId(owner, "/api/project/" + pid(project) + "/item", "{\"title\":\"Repeated\"}");
        itemService.attachToTrail(second, shared, owner);
        itemService.updateContent(shared, "{\"root\":{\"type\":\"root\",\"children\":[{\"type\":\"paragraph\",\"children\":[{\"type\":\"text\",\"text\":\"Shared body\",\"format\":3}]}]}}", owner);
        mockMvc.perform(put("/api/item/" + shared).header("Authorization", bearer(owner)).contentType("application/json").content("{\"titleAlign\":\"right\"}")).andExpect(status().isOk());
        return new Fixture(owner, project, first, second, shared, loose);
    }
    byte[] download(Fixture f) throws Exception {
        return mockMvc.perform(get("/api/project/" + pid(f.project) + "/export").header("Authorization", bearer(f.owner)))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Cache-Control", "private, no-store")).andReturn().getResponse().getContentAsByteArray();
    }
    Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) { assertThat(entries.put(entry.getName(), zip.readAllBytes())).isNull(); }
        }
        return entries;
    }
    String html(Map<String, byte[]> entries) { return new String(entries.get("index.html"), StandardCharsets.UTF_8); }
    JsonNode json(Map<String, byte[]> entries) { return mapper.readTree(new String(entries.get("project.json"), StandardCharsets.UTF_8)); }

    @Test
    void sharedNotesOrderAnnotationsAndRelationsArePreservedWithoutChangingTheOriginal() throws Exception {
        Fixture f = fixture();
        mockMvc.perform(post("/api/item/" + f.shared + "/tie").header("Authorization", bearer(f.owner)).contentType("application/json")
                .content("{\"text\":\"Context\",\"targetId\":" + f.loose + "}")).andExpect(status().isOk());
        long relation = jdbcTemplate.queryForObject("SELECT id FROM association WHERE source_item_id = ?", Long.class, f.shared);
        Date edited = projects.findById(f.project.getId()).orElseThrow().getLastEditedDate();
        String original = itemService.getContent(f.shared, f.owner).getContent();
        long itemCount = items.count();
        long snapshots = jdbcTemplate.queryForObject("SELECT count(*) FROM project_snapshot", Long.class);
        Map<String, byte[]> entries = unzip(download(f));
        JsonNode data = json(entries);
        assertThat(data.path("formatVersion").asInt()).isEqualTo(2);
        assertThat(data.path("exportedAt").asText()).isNotBlank();
        assertThat(data.path("items").size()).isEqualTo(2);
        assertThat(data.path("trails").size()).isEqualTo(2);
        assertThat(data.path("trails").get(0).path("steps").get(0).path("itemId").asLong()).isEqualTo(f.shared);
        assertThat(data.path("items").get(0).path("content").asText()).isEqualTo(original);
        assertThat(data.path("items").get(0).path("titleAlign").asText()).isEqualTo("right");
        assertThat(data.path("looseItemIds").get(0).asLong()).isEqualTo(f.loose);
        assertThat(data.path("associations").get(0).path("text").asText()).isEqualTo("Context");
        assertThat(html(entries)).contains("—", "Context", "Notes outside trails", "<em><strong>Shared body</strong></em>");
        assertThat(html(entries).split("Shared body", -1)).hasSize(3);
        var anchors = java.util.regex.Pattern.compile("id=\"([^\"]+)\"").matcher(html(entries));
        Set<String> unique = new HashSet<>();
        while (anchors.find()) assertThat(unique.add(anchors.group(1))).isTrue();
        assertThat(items.count()).isEqualTo(itemCount);
        assertThat(itemService.getContent(f.shared, f.owner).getContent()).isEqualTo(original);
        assertThat(projects.findById(f.project.getId()).orElseThrow().getLastEditedDate()).isEqualTo(edited);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM project_snapshot", Long.class)).isEqualTo(snapshots);
        assertThat(data.toString()).doesNotContain("password", "accessToken", "refreshToken", "email", "owner");
    }

    @Test
    void richNodesInternalLinksAndUnsafeContentHaveOfflineRepresentations() throws Exception {
        Fixture f = fixture();
        String content = mapper.writeValueAsString(Map.of("root", Map.of("type", "root", "children", List.of(
                Map.of("type", "heading", "tag", "h2", "children", List.of(Map.of("type", "text", "text", "Heading"))),
                Map.of("type", "quote", "children", List.of(Map.of("type", "text", "text", "Quote"))),
                Map.of("type", "list", "listType", "number", "start", 3, "children", List.of(Map.of("type", "listitem", "checked", true, "children", List.of(Map.of("type", "text", "text", "Checked"))))),
                Map.of("type", "code", "language", "javascript", "children", List.of(Map.of("type", "code-highlight", "text", "<script>alert(1)</script>"))),
                Map.of("type", "link", "url", "#", "rel", "tramo-idea:" + f.loose, "children", List.of(Map.of("type", "text", "text", "Internal"))),
                Map.of("type", "link", "url", "javascript:alert(1)", "children", List.of(Map.of("type", "text", "text", "Unsafe"))),
                Map.of("type", "link", "url", "https://example.com", "children", List.of(Map.of("type", "text", "text", "External"))),
                Map.of("type", "equation", "equation", "x^2+1", "inline", true), Map.of("type", "music", "abc", "X:1\nK:C\nCDE"),
                Map.of("type", "horizontalrule"), Map.of("type", "table", "children", List.of(Map.of("type", "tablerow", "children", List.of(Map.of("type", "tablecell", "headerState", 1, "colSpan", 2, "children", List.of(Map.of("type", "text", "text", "Cell"))))))),
                Map.of("type", "future-node", "payload", "Unrecognized preserved")))));
        itemService.updateContent(f.shared, content, f.owner);
        var entries = unzip(download(f));
        String html = html(entries);
        assertThat(html).contains("&lt;script&gt;", "&lt;img", "<h2>Heading</h2>", "<blockquote>", "<ol start=\"3\">", "type=\"checkbox\" disabled checked", "<pre data-language=\"javascript\">", "href=\"#note-" + f.loose, "href=\"https://example.com\"", "x^2+1", "X:1", "colspan=\"2\"", "Unrecognized preserved", "Export warnings");
        assertThat(html).doesNotContain("<script", "javascript:alert(1)", "<img src=x", "<iframe", "fetch(", "http-equiv=\"refresh\"");
        assertThat(json(entries).path("warnings").size()).isGreaterThanOrEqualTo(4);
        assertThat(json(entries).path("items").get(0).path("content").asText()).isEqualTo(content);
    }

    UUID image(Fixture f, boolean inherited) {
        User owner = inherited ? createUser("imageowner") : f.owner;
        Project project = inherited ? createProject(owner, "Source image", "PRIVATE") : f.project;
        UUID id = UUID.randomUUID();
        UploadRecord upload = new UploadRecord();
        upload.setUserId(owner.getId()); upload.setProjectId(project.getId()); upload.setObjectKey("images/" + id); upload.setBytes((long) PNG.length); upload.setCreatedDate(new Date());
        upload = uploads.saveAndFlush(upload);
        images.createObject(id, "image/png", PNG.length, "a".repeat(64));
        images.createImage(id, id, project.getId(), upload.getId()); images.setState(id, "READY");
        if (inherited) images.replaceItemReferences(f.shared, List.of(id));
        return id;
    }
    String imageContent(UUID id) { return "{\"root\":{\"type\":\"root\",\"children\":[{\"type\":\"image\",\"version\":2,\"imageId\":\"" + id + "\",\"altText\":\"Offline picture\",\"caption\":\"Caption\"},{\"type\":\"image\",\"version\":2,\"imageId\":\"" + id + "\"}]}}"; }

    @Test
    void inheritedPrivateImagesAreIncludedOnceAndLinkedRelatively() throws Exception {
        Fixture f = fixture(); UUID id = image(f, true);
        itemService.updateContent(f.shared, imageContent(id), f.owner);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) PNG.length).build(), new ByteArrayInputStream(PNG)));
        var entries = unzip(download(f));
        String path = json(entries).path("assets").path(id.toString()).asText();
        assertThat(path).isEqualTo("assets/" + id + ".png");
        assertThat(entries.keySet().stream().filter(name -> name.startsWith("assets/")).count()).isEqualTo(1);
        assertThat(entries.get(path)).isEqualTo(PNG);
        assertThat(html(entries)).contains("src=\"" + path + "\"", "Caption").doesNotContain("https://", "/api/", "temporary/");
        verify(s3Client, times(1)).getObject(any(GetObjectRequest.class));
    }

    @Test
    void missingResourcesAndUnauthorizedUsersNeverReceivePartialArchives() throws Exception {
        Fixture f = fixture(); UUID id = image(f, false);
        itemService.updateContent(f.shared, imageContent(id), f.owner);
        User stranger = createUser("stranger");
        mockMvc.perform(get("/api/project/" + pid(f.project) + "/export").header("Authorization", bearer(stranger))).andExpect(status().isForbidden());
        verify(s3Client, never()).getObject(any(GetObjectRequest.class));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());
        mockMvc.perform(get("/api/project/" + pid(f.project) + "/export").header("Authorization", bearer(f.owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXPORT_RESOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(id.toString())));
        assertThat(itemService.getContent(f.shared, f.owner).getContent()).isEqualTo(imageContent(id));
    }

    @Test
    void arbitraryImageUrlsAndPrivateIdsWithoutReferencesAreRejected() throws Exception {
        Fixture f = fixture(); UUID id = image(f, false);
        items.findById(f.shared).orElseThrow();
        jdbcTemplate.update("UPDATE item_content SET content = ? WHERE id = (SELECT content_id FROM item WHERE id = ?)", imageContent(id), f.shared);
        mockMvc.perform(get("/api/project/" + pid(f.project) + "/export").header("Authorization", bearer(f.owner))).andExpect(status().isConflict());
        jdbcTemplate.update("UPDATE item_content SET content = ? WHERE id = (SELECT content_id FROM item WHERE id = ?)", "{\"root\":{\"children\":[{\"type\":\"image\",\"src\":\"http://127.0.0.1/secret\"}]}}", f.shared);
        mockMvc.perform(get("/api/project/" + pid(f.project) + "/export").header("Authorization", bearer(f.owner))).andExpect(status().isConflict());
        verify(s3Client, never()).getObject(any(GetObjectRequest.class));
    }

    @Test
    void capturesOneDatabaseSnapshotEvenIfContentChangesDuringPreparation() throws Exception {
        Fixture f = fixture(); String original = itemService.getContent(f.shared, f.owner).getContent();
        CountDownLatch reading = new CountDownLatch(1), resume = new CountDownLatch(1);
        var delegate = mockingDetails(items).getMockCreationSettings().getDefaultAnswer();
        doAnswer(call -> { reading.countDown(); assertThat(resume.await(15, TimeUnit.SECONDS)).isTrue(); return delegate.answer(call); }).when(items).findForExport(eq(f.project.getId()));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Path> future = pool.submit(() -> exports.prepare(f.project.getId(), f.owner));
            assertThat(reading.await(15, TimeUnit.SECONDS)).isTrue();
            itemService.updateContent(f.shared, "{\"root\":{\"children\":[{\"type\":\"text\",\"text\":\"new content\"}]}}", f.owner);
            resume.countDown();
            Path archive = future.get(20, TimeUnit.SECONDS);
            try { assertThat(json(unzip(Files.readAllBytes(archive))).path("items").get(0).path("content").asText()).isEqualTo(original); }
            finally { exports.discard(archive); }
        } finally { resume.countDown(); pool.shutdownNow(); }
        assertThat(itemService.getContent(f.shared, f.owner).getContent()).contains("new content");
    }

    @Test
    void preservesReorderedStepsAndTheirAnnotations() throws Exception {
        Fixture f = fixture();
        long last = postForId(f.owner, "/api/trail/" + f.first + "/item", "{\"title\":\"Repeated\"}");
        itemService.reorderTrailItems(f.first, List.of(last, f.shared), f.owner);
        var entries = unzip(download(f));
        var ordered = json(entries).path("trails").get(0).path("steps");
        assertThat(ordered.get(0).path("itemId").asLong()).isEqualTo(last);
        assertThat(ordered.get(0).path("orderIndex").asInt()).isLessThan(ordered.get(1).path("orderIndex").asInt());
        assertThat(json(entries).path("items").size()).isEqualTo(3);
    }

    @Test
    void includesAuthorizedLegacyImagesAndProjectThumbnailFromKnownStorage() throws Exception {
        Fixture f = fixture();
        String imageUrl = publicStorage.publicUrlFor("editor-image/" + f.owner.getId() + "/legacy.png");
        String thumbnail = publicStorage.publicUrlFor("thumbnail/" + f.owner.getId() + "/cover.png");
        String content = mapper.writeValueAsString(Map.of("root", Map.of("type", "root", "children", List.of(Map.of("type", "image", "src", imageUrl)))));
        jdbcTemplate.update("UPDATE item_content SET content = ? WHERE id = (SELECT content_id FROM item WHERE id = ?)", content, f.shared);
        var reference = new com.tramo.backend.trail.entity.ItemImageReference();
        reference.setItem(items.findById(f.shared).orElseThrow()); reference.setUrl(imageUrl); legacyReferences.saveAndFlush(reference);
        f.project.setThumbnailType(com.tramo.backend.project.entity.ProjectThumbnailType.DEDICATED); f.project.setThumbnailImageUrl(thumbnail); projects.saveAndFlush(f.project);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(call -> new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) PNG.length).build(), new ByteArrayInputStream(PNG)));
        var entries = unzip(download(f));
        var mapping = json(entries).path("assets");
        assertThat(mapping.size()).isEqualTo(2);
        assertThat(entries.get(mapping.path(imageUrl).asText())).isEqualTo(PNG);
        assertThat(entries.get(mapping.path(thumbnail).asText())).isEqualTo(PNG);
        assertThat(html(entries)).contains("Project thumbnail", "src=\"" + mapping.path(imageUrl).asText() + "\"").doesNotContain(imageUrl, thumbnail);
        verify(s3Client, times(2)).getObject(any(GetObjectRequest.class));
    }

    @Test
    void rejectsOversizedProjectsBeforeLoadingTheirBodies() throws Exception {
        Fixture f = fixture();
        jdbcTemplate.update("UPDATE item_content SET content = repeat('x', 8388609) WHERE id = (SELECT content_id FROM item WHERE id = ?)", f.shared);
        assertThatThrownBy(() -> exports.prepare(f.project.getId(), f.owner)).isInstanceOf(ProjectExportException.class).hasMessageContaining("limits");
        verify(items, never()).findForExport(any());
    }
}
